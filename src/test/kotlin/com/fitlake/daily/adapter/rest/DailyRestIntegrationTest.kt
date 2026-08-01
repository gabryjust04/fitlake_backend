package com.fitlake.daily.adapter.rest

import com.fitlake.auth.infrastructure.OpenApiConfig
import com.fitlake.auth.infrastructure.RestAccessDeniedHandler
import com.fitlake.auth.infrastructure.RestAuthenticationEntryPoint
import com.fitlake.auth.infrastructure.SecurityConfig
import com.fitlake.auth.infrastructure.SecurityCurrentUserProvider
import com.fitlake.auth.infrastructure.firebase.FirebaseAuthenticationFilter
import com.fitlake.auth.infrastructure.firebase.FirebaseTokenClaims
import com.fitlake.auth.infrastructure.firebase.FirebaseTokenVerificationException
import com.fitlake.auth.infrastructure.firebase.FirebaseTokenVerifier
import com.fitlake.daily.application.DailyQueryService
import com.fitlake.daily.application.capture.CaptureConfirmationService
import com.fitlake.daily.application.capture.DailyCaptureContentFactory
import com.fitlake.daily.application.capture.DailyCaptureEditService
import com.fitlake.daily.application.capture.DailyManualCaptureService
import com.fitlake.daily.application.capture.DailyCaptureService
import com.fitlake.daily.application.port.DailyOwnedUserFood
import com.fitlake.daily.application.port.DailyUserFoodLookupPort
import com.fitlake.daily.domain.capture.DailyFoodBasisSnapshot
import com.fitlake.daily.domain.capture.DailyFoodConversionSnapshot
import com.fitlake.daily.domain.capture.DailyFoodDefaultServingSnapshot
import com.fitlake.daily.domain.capture.DailyFoodItemSourceType
import com.fitlake.daily.domain.capture.DailyFoodSnapshotUnit
import com.fitlake.daily.domain.capture.DailyNutritionSourceSnapshot
import com.fitlake.daily.domain.capture.DailyNutritionValues
import com.fitlake.daily.application.finalization.DailyFinalizationService
import com.fitlake.daily.application.finalization.DailyDayReopeningService
import com.fitlake.daily.application.finalization.DailyMetricsProjectionService
import com.fitlake.support.ImmediateTransactionExecutor
import com.fitlake.support.InMemoryDailyCaptureAuditRepository
import com.fitlake.support.InMemoryDailyCaptureRepository
import com.fitlake.support.InMemoryDailyDayRepository
import com.fitlake.support.InMemoryDailyMetricsRepository
import com.fitlake.support.InMemoryUserAccountRepository
import com.fitlake.support.InMemoryUserAuthIdentityRepository
import com.fitlake.user.application.UserProvisioningService
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.SpringBootConfiguration
import org.springframework.boot.autoconfigure.EnableAutoConfiguration
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.MockHttpServletRequestDsl
import org.springframework.test.web.servlet.delete
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.patch
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.put
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.math.BigDecimal
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.test.assertEquals

@SpringBootTest(
	classes = [DailyRestIntegrationTest.TestApplication::class],
	properties = [
		"OPENAI_API_KEY=test-only",
		"FIREBASE_PROJECT_ID=fitlake-test",
		"management.endpoints.web.exposure.include=health",
		"spring.ai.model.chat=none",
	],
)
@AutoConfigureMockMvc
class DailyRestIntegrationTest @Autowired constructor(
	private val mockMvc: MockMvc,
	private val audits: InMemoryDailyCaptureAuditRepository,
) {
	@Test
	fun `daily endpoints require authentication`() {
		mockMvc.get("/api/daily/days/2026-08-01")
			.andExpect {
				status { isUnauthorized() }
				jsonPath("$.error") { value("unauthorized") }
			}
	}

	@Test
	fun `manual REST flow creates accepts finalizes and reads a day`() {
		val created = createFieldsCapture("2026-08-01", "valid-a")
		created.andExpect {
			status { isCreated() }
			jsonPath("$.status") { value("OPEN") }
			jsonPath("$.payload.schemaVersion") { value(2) }
			jsonPath("$.payload.entries[0].type") { value("WEIGHT") }
			jsonPath("$.payload.entries[0].value") { value(78.4) }
		}
		val captureId = captureId(created.andReturn().response.contentAsString)

		mockMvc.post("/api/daily/captures/$captureId/accept") {
			auth("valid-a")
		}.andExpect {
			status { isOk() }
			jsonPath("$.status") { value("ACCEPTED") }
		}

		mockMvc.post("/api/daily/days/2026-08-01/finalize") {
			auth("valid-a")
		}.andExpect {
			status { isOk() }
			jsonPath("$.status") { value("CONFIRMED") }
			jsonPath("$.bodyWeightKg") { value(78.4) }
			jsonPath("$.generatedFromCaptureIds[0]") { value(captureId) }
		}

		mockMvc.get("/api/daily/days/2026-08-01") {
			auth("valid-a")
		}.andExpect {
			status { isOk() }
			jsonPath("$.status") { value("CONFIRMED") }
			jsonPath("$.captures[0].status") { value("ACCEPTED") }
			jsonPath("$.metrics.bodyWeightKg") { value(78.4) }
		}

		mockMvc.get("/api/daily/days/2026-08-01/metrics") {
			auth("valid-a")
		}.andExpect {
			status { isOk() }
			jsonPath("$.bodyWeightKg") { value(78.4) }
		}

		mockMvc.post("/api/daily/days/2026-08-01/finalize") {
			auth("valid-a")
		}.andExpect {
			status { isOk() }
			jsonPath("$.generatedFromCaptureIds.length()") { value(1) }
		}

		createFieldsCapture("2026-08-01", "valid-a").andExpect {
			status { isConflict() }
			jsonPath("$.error") { value("conflict") }
		}
	}

	@Test
	fun `rejection and soft delete preserve expected state`() {
		val foodId = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa")
		val created = mockMvc.post("/api/daily/days/2026-08-05/captures") {
			auth("valid-a")
			contentType = MediaType.APPLICATION_JSON
			content = typedFoodCaptureBody(foodId)
		}.andExpect {
			status { isCreated() }
			jsonPath("$.payload.entries[0].items[0].itemId") { isNotEmpty() }
		}
		val captureId = captureId(created.andReturn().response.contentAsString)

		mockMvc.delete("/api/daily/captures/$captureId") {
			auth("valid-a")
		}.andExpect {
			status { isOk() }
			jsonPath("$.status") { value("SOFT_DELETED") }
		}

		val rejected = createFieldsCapture("2026-08-05", "valid-a")
		val rejectedId = captureId(rejected.andReturn().response.contentAsString)
		mockMvc.post("/api/daily/captures/$rejectedId/reject") {
			auth("valid-a")
		}.andExpect {
			status { isOk() }
			jsonPath("$.status") { value("REJECTED") }
		}

		mockMvc.post("/api/daily/days/2026-08-05/finalize") {
			auth("valid-a")
		}.andExpect {
			status { isOk() }
			jsonPath("$.generatedFromCaptureIds.length()") { value(0) }
			jsonPath("$.foodLog.length()") { value(0) }
		}
	}

	@Test
	fun `open captures block day finalization`() {
		createFieldsCapture("2026-08-02", "valid-a")

		mockMvc.post("/api/daily/days/2026-08-02/finalize") {
			auth("valid-a")
		}.andExpect {
			status { isConflict() }
			jsonPath("$.error") { value("conflict") }
		}
	}

	@Test
	fun `confirmed day can be reopened edited and finalized into the updated metrics snapshot`() {
		val created = createFieldsCapture("2026-08-20", "valid-a").andExpect { status { isCreated() } }
		val response = created.andReturn().response.contentAsString
		val captureId = captureId(response)
		val weightEntryId = responseUuid(response, "entryId")

		mockMvc.post("/api/daily/captures/$captureId/accept") { auth("valid-a") }
			.andExpect { status { isOk() } }
		mockMvc.post("/api/daily/days/2026-08-20/finalize") { auth("valid-a") }
			.andExpect {
				status { isOk() }
				jsonPath("$.status") { value("CONFIRMED") }
				jsonPath("$.bodyWeightKg") { value(78.4) }
			}

		mockMvc.post("/api/daily/days/2026-08-20/reopen") { auth("valid-a") }
			.andExpect {
				status { isOk() }
				jsonPath("$.status") { value("REOPENED") }
				jsonPath("$.reopenedAt") { isNotEmpty() }
				jsonPath("$.metrics.status") { value("REOPENED") }
				jsonPath("$.metrics.bodyWeightKg") { value(78.4) }
			}

		mockMvc.put("/api/daily/captures/$captureId") {
			auth("valid-a")
			contentType = MediaType.APPLICATION_JSON
			content = """
				{
				  "version": 1,
				  "entries": [{
				    "entryId": "$weightEntryId",
				    "type": "WEIGHT",
				    "value": 80.2,
				    "unit": "KILOGRAM"
				  }]
				}
			""".trimIndent()
		}.andExpect {
			status { isOk() }
			jsonPath("$.status") { value("ACCEPTED") }
			jsonPath("$.version") { value(2) }
			jsonPath("$.payload.entries[0].value") { value(80.2) }
		}

		mockMvc.post("/api/daily/days/2026-08-20/finalize") { auth("valid-a") }
			.andExpect {
				status { isOk() }
				jsonPath("$.status") { value("CONFIRMED") }
				jsonPath("$.bodyWeightKg") { value(80.2) }
				jsonPath("$.recalculatedAt") { isNotEmpty() }
				jsonPath("$.generatedFromCaptureIds[0]") { value(captureId) }
			}

		mockMvc.get("/api/daily/days/2026-08-20") { auth("valid-a") }
			.andExpect {
				status { isOk() }
				jsonPath("$.status") { value("CONFIRMED") }
				jsonPath("$.reopenedAt") { isNotEmpty() }
				jsonPath("$.metrics.status") { value("CONFIRMED") }
				jsonPath("$.metrics.bodyWeightKg") { value(80.2) }
			}
	}

	@Test
	fun `legacy capture request is rejected`() {
		mockMvc.post("/api/daily/days/2026-08-03/captures") {
			auth("valid-a")
			contentType = MediaType.APPLICATION_JSON
			content = """
				{
				  "type": "FOOD",
				  "meals": [{
				    "mealName": "colazione",
				    "items": [{"foodName": "avena", "quantity": 40, "unit": "secchio"}]
				  }]
				}
			""".trimIndent()
			}.andExpect {
				status { isBadRequest() }
				jsonPath("$.error") { value("invalid_request") }
			}
		}

	@Test
	fun `removed partial update routes are not mapped`() {
		val foodId = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb")
		val created = mockMvc.post("/api/daily/days/2026-08-06/captures") {
			auth("valid-a")
			contentType = MediaType.APPLICATION_JSON
			content = typedFoodCaptureBody(foodId)
		}.andExpect { status { isCreated() } }
		val response = created.andReturn().response.contentAsString
		val captureId = captureId(response)
		val itemId = responseUuid(response, "itemId")

		mockMvc.patch("/api/daily/captures/$captureId/food-items/$itemId") {
			auth("valid-a")
			contentType = MediaType.APPLICATION_JSON
			content = """{"quantity":50,"unit":"GRAM"}"""
		}.andExpect { status { is4xxClientError() } }

		mockMvc.put("/api/daily/captures/$captureId/content") {
			auth("valid-a")
			contentType = MediaType.APPLICATION_JSON
			content = """{"version":0,"entries":[]}"""
		}.andExpect { status { is4xxClientError() } }
	}

	@Test
	fun `capture ownership is enforced`() {
		val created = createFieldsCapture("2026-08-04", "valid-a")
		val captureId = captureId(created.andReturn().response.contentAsString)

		mockMvc.post("/api/daily/captures/$captureId/accept") {
			auth("valid-b")
		}.andExpect {
			status { isNotFound() }
		}
	}

	@Test
	fun `typed food and mixed POST derive capture type and return server calculated snapshots`() {
		val foodId = UUID.fromString("11111111-1111-1111-1111-111111111111")
		mockMvc.post("/api/daily/days/2026-08-10/captures") {
			auth("valid-a")
			contentType = MediaType.APPLICATION_JSON
			content = typedFoodCaptureBody(foodId, amount = "170", unit = "GRAM", includeForgedValues = true)
		}.andExpect {
			status { isCreated() }
			jsonPath("$.captureType") { value("FOOD") }
			jsonPath("$.status") { value("OPEN") }
			jsonPath("$.version") { value(0) }
			jsonPath("$.payload.schemaVersion") { value(2) }
			jsonPath("$.payload.entries.length()") { value(1) }
			jsonPath("$.payload.entries[0].entryId") { isNotEmpty() }
			jsonPath("$.payload.entries[0].type") { value("FOOD") }
			jsonPath("$.payload.entries[0].items[0].itemId") { isNotEmpty() }
			jsonPath("$.payload.entries[0].items[0].userFoodId") { value(foodId.toString()) }
			jsonPath("$.payload.entries[0].items[0].displayName") { value("Owned test food") }
			jsonPath("$.payload.entries[0].items[0].brand") { value("FitLake Test") }
			jsonPath("$.payload.entries[0].items[0].enteredQuantity.amount") { value(170) }
			jsonPath("$.payload.entries[0].items[0].enteredQuantity.unit") { value("GRAM") }
			jsonPath("$.payload.entries[0].items[0].resolvedQuantity.amount") { value(170) }
			jsonPath("$.payload.entries[0].items[0].resolvedQuantity.unit") { value("GRAM") }
			jsonPath("$.payload.entries[0].items[0].nutritionBasisSnapshot.amount") { value(100) }
			jsonPath("$.payload.entries[0].items[0].nutrientsPerBasisSnapshot.caloriesKcal") { value(62) }
			jsonPath("$.payload.entries[0].items[0].calculatedNutrition.caloriesKcal") { value(105.4) }
			jsonPath("$.payload.entries[0].items[0].calculatedNutrition.proteinGrams") { value(16.15) }
			jsonPath("$.payload.entries[0].items[0].nutritionSourceSnapshot.originalSourceType") {
				value("PRODUCT_LABEL")
			}
			jsonPath("$.payload.entries[0].nutritionTotal.caloriesKcal") { value(105.4) }
			jsonPath("$.payload.type") { doesNotExist() }
			jsonPath("$.payload.meals") { doesNotExist() }
			jsonPath("$.payload.fields") { doesNotExist() }
		}

		mockMvc.post("/api/daily/days/2026-08-11/captures") {
			auth("valid-a")
			contentType = MediaType.APPLICATION_JSON
			content = typedMixedCaptureBody(foodId)
		}.andExpect {
			status { isCreated() }
			jsonPath("$.captureType") { value("MIXED") }
			jsonPath("$.payload.entries.length()") { value(2) }
			jsonPath("$.payload.entries[0].items[0].enteredQuantity.unit") { value("DEFAULT_SERVING") }
			jsonPath("$.payload.entries[0].items[0].resolvedQuantity.amount") { value(170) }
			jsonPath("$.payload.entries[1].type") { value("WEIGHT") }
			jsonPath("$.payload.entries[1].value") { value(78) }
			jsonPath("$.payload.entries[1].unit") { value("KILOGRAM") }
			jsonPath("$.payload.entries[1].value") { value(78) }
		}
	}

	@Test
	fun `typed capture reads are user scoped and foreign food and capture remain hidden`() {
		val foodId = UUID.fromString("22222222-2222-2222-2222-222222222222")
		val created = mockMvc.post("/api/daily/days/2026-08-12/captures") {
			auth("valid-a")
			contentType = MediaType.APPLICATION_JSON
			content = typedFoodCaptureBody(foodId)
		}.andExpect { status { isCreated() } }
		val response = created.andReturn().response.contentAsString
		val captureId = captureId(response)
		val entryId = responseUuid(response, "entryId")
		val itemId = responseUuid(response, "itemId")

		mockMvc.get("/api/daily/captures/$captureId") { auth("valid-a") }.andExpect {
			status { isOk() }
			jsonPath("$.captureId") { value(captureId) }
			jsonPath("$.payload.entries[0].items[0].userFoodId") { value(foodId.toString()) }
		}
		mockMvc.get("/api/daily/days/2026-08-12/captures") { auth("valid-a") }.andExpect {
			status { isOk() }
			jsonPath("$.length()") { value(1) }
			jsonPath("$[0].captureId") { value(captureId) }
		}

		mockMvc.get("/api/daily/captures/$captureId") { auth("valid-b") }.andExpect {
			status { isNotFound() }
			jsonPath("$.error") { value("not_found") }
		}
		mockMvc.get("/api/daily/days/2026-08-12/captures") { auth("valid-b") }.andExpect {
			status { isNotFound() }
			jsonPath("$.error") { value("not_found") }
		}
		mockMvc.put("/api/daily/captures/$captureId") {
			auth("valid-b")
			contentType = MediaType.APPLICATION_JSON
			content = replaceFoodContentBody(0, entryId, itemId, foodId, "170", "GRAM")
		}.andExpect {
			status { isNotFound() }
			jsonPath("$.error") { value("not_found") }
		}

		mockMvc.post("/api/daily/days/2026-08-13/captures") {
			auth("valid-b")
			contentType = MediaType.APPLICATION_JSON
			content = typedFoodCaptureBody(foodId)
		}.andExpect {
			status { isNotFound() }
			jsonPath("$.error") { value("not_found") }
		}
	}

	@Test
	fun `full content PUT increments version writes audit and leaves stale replacement unchanged`() {
		val foodId = UUID.fromString("33333333-3333-3333-3333-333333333333")
		val created = mockMvc.post("/api/daily/days/2026-08-14/captures") {
			auth("valid-a")
			contentType = MediaType.APPLICATION_JSON
			content = typedFoodCaptureBody(foodId)
		}.andExpect { status { isCreated() } }
		val response = created.andReturn().response.contentAsString
		val captureId = captureId(response)
		val entryId = responseUuid(response, "entryId")
		val itemId = responseUuid(response, "itemId")
		val auditCountBefore = audits.count()
		val replacement = replaceFoodContentBody(
			version = 0,
			entryId = entryId,
			itemId = itemId,
			foodId = foodId,
			amount = "1",
			unit = "DEFAULT_SERVING",
		)

		mockMvc.put("/api/daily/captures/$captureId") {
			auth("valid-a")
			contentType = MediaType.APPLICATION_JSON
			content = replacement.replace("\"version\": 0,", "")
		}.andExpect {
			status { isBadRequest() }
			jsonPath("$.error") { value("validation_error") }
		}
		mockMvc.put("/api/daily/captures/$captureId") {
			auth("valid-a")
			contentType = MediaType.APPLICATION_JSON
			content = replacement.replace("\"version\": 0", "\"version\": null")
		}.andExpect {
			status { isBadRequest() }
			jsonPath("$.error") { value("validation_error") }
		}

		mockMvc.put("/api/daily/captures/$captureId") {
			auth("valid-a")
			header("X-Request-ID", "rest-edit-1")
			contentType = MediaType.APPLICATION_JSON
			content = replacement
		}.andExpect {
			status { isOk() }
			jsonPath("$.captureId") { value(captureId) }
			jsonPath("$.status") { value("OPEN") }
			jsonPath("$.version") { value(1) }
			jsonPath("$.payload.entries[0].entryId") { value(entryId) }
			jsonPath("$.payload.entries[0].items[0].itemId") { value(itemId) }
			jsonPath("$.payload.entries[0].items[0].enteredQuantity.amount") { value(1) }
			jsonPath("$.payload.entries[0].items[0].enteredQuantity.unit") { value("DEFAULT_SERVING") }
			jsonPath("$.payload.entries[0].items[0].resolvedQuantity.amount") { value(170) }
			jsonPath("$.payload.entries[0].items[0].calculatedNutrition.caloriesKcal") { value(105.4) }
		}

		assertEquals(auditCountBefore + 1, audits.count())
		val audit = audits.all().single { it.captureId.value.toString() == captureId }
		assertEquals(0L, audit.oldVersion)
		assertEquals(1L, audit.newVersion)
		assertEquals("rest-edit-1", audit.requestId)
		assertEquals(BigDecimal("100"), audit.oldPayload.entries.single().items.single().enteredQuantity.amount)
		assertEquals(BigDecimal.ONE, audit.newPayload.entries.single().items.single().enteredQuantity.amount)

		mockMvc.put("/api/daily/captures/$captureId") {
			auth("valid-a")
			contentType = MediaType.APPLICATION_JSON
			content = replacement
		}.andExpect {
			status { isConflict() }
			jsonPath("$.error") { value("conflict") }
		}
		assertEquals(auditCountBefore + 1, audits.count())

		mockMvc.get("/api/daily/captures/$captureId") { auth("valid-a") }.andExpect {
			status { isOk() }
			jsonPath("$.version") { value(1) }
			jsonPath("$.payload.entries[0].items[0].enteredQuantity.amount") { value(1) }
			jsonPath("$.payload.entries[0].items[0].calculatedNutrition.caloriesKcal") { value(105.4) }
		}
	}

	@Test
	fun `OpenAPI exposes Daily REST operations`() {
		mockMvc.get("/v3/api-docs")
			.andExpect {
				status { isOk() }
				jsonPath("$.paths['/api/daily/days/{date}/captures']") { exists() }
				jsonPath("$.paths['/api/daily/days/{date}/captures'].get") { exists() }
				jsonPath("$.paths['/api/daily/captures/{captureId}']") { exists() }
				jsonPath("$.paths['/api/daily/captures/{captureId}'].put") { exists() }
				jsonPath("$.paths['/api/daily/captures/{captureId}'].put.requestBody.content['application/json'].examples['Full content replacement']") { exists() }
				jsonPath("$.paths['/api/daily/captures/{captureId}'].put.responses['200'].content['*/*'].examples['Updated calculated capture']") { exists() }
				jsonPath("$.paths['/api/daily/captures/{captureId}/content']") { doesNotExist() }
				jsonPath("$.paths['/api/daily/captures/{captureId}/food-items/{itemTempId}']") { doesNotExist() }
				jsonPath("$.paths['/api/daily/days/{date}/finalize']") { exists() }
				jsonPath("$.paths['/api/daily/days/{date}/reopen']") { exists() }
			}
	}

	private fun createFieldsCapture(date: String, token: String) =
		mockMvc.post("/api/daily/days/$date/captures") {
			auth(token)
			contentType = MediaType.APPLICATION_JSON
			content = """
				{
				  "entries": [
				    {"type": "WEIGHT", "value": 78.4, "unit": "KILOGRAM"},
				    {"type": "SLEEP", "value": 7.5, "unit": "HOUR"}
				  ]
				}
			""".trimIndent()
		}

	private fun typedFoodCaptureBody(
		foodId: UUID,
		amount: String = "100",
		unit: String = "GRAM",
		includeForgedValues: Boolean = false,
	): String {
		val forgedValues = if (includeForgedValues) {
			""",
          "displayName": "forged client name",
          "calculatedNutrition": {"caloriesKcal": 999999}
"""
		} else {
			""
		}
		return """
			{
			  "entries": [{
			    "type": "FOOD",
			    "mealType": "BREAKFAST",
			    "mealLabel": "breakfast",
			    "items": [{
			      "sourceType": "USER_FOOD",
			      "userFoodId": "$foodId",
			      "quantity": {"amount": $amount, "unit": "$unit"}$forgedValues
			    }]
			  }]
			}
		""".trimIndent()
	}

	private fun typedMixedCaptureBody(foodId: UUID) = """
		{
		  "entries": [
		    {
		      "type": "FOOD",
		      "mealType": "BREAKFAST",
		      "items": [{
		        "sourceType": "USER_FOOD",
		        "userFoodId": "$foodId",
		        "quantity": {"amount": 1, "unit": "DEFAULT_SERVING"}
		      }]
		    },
		    {"type": "WEIGHT", "value": 78000, "unit": "GRAM"}
		  ]
		}
	""".trimIndent()

	private fun replaceFoodContentBody(
		version: Long,
		entryId: String,
		itemId: String,
		foodId: UUID,
		amount: String,
		unit: String,
	) = """
		{
		  "version": $version,
		  "entries": [{
		    "entryId": "$entryId",
		    "type": "FOOD",
		    "mealType": "BREAKFAST",
		    "items": [{
		      "itemId": "$itemId",
		      "sourceType": "USER_FOOD",
		      "userFoodId": "$foodId",
		      "quantity": {"amount": $amount, "unit": "$unit"}
		    }]
		  }]
		}
	""".trimIndent()

	private fun captureId(response: String): String =
		requireNotNull(Regex("\"captureId\":\"([^\"]+)\"").find(response)?.groupValues?.get(1))

	private fun responseUuid(response: String, field: String): String =
		requireNotNull(Regex("\"$field\":\"([^\"]+)\"").find(response)?.groupValues?.get(1))

	private fun MockHttpServletRequestDsl.auth(token: String) {
		header("Authorization", "Bearer $token")
	}

	class TestUserFoodLookup : DailyUserFoodLookupPort {
		private val owners = ConcurrentHashMap<UUID, com.fitlake.user.domain.UserId>()

		override fun findActiveOwnedFood(
			userId: com.fitlake.user.domain.UserId,
			userFoodId: UUID,
		): DailyOwnedUserFood? {
			val owner = owners.putIfAbsent(userFoodId, userId) ?: userId
			if (owner != userId) return null
			return DailyOwnedUserFood(
				userFoodId = userFoodId,
				displayName = "Owned test food",
				brand = "FitLake Test",
				nutritionBasis = DailyFoodBasisSnapshot(BigDecimal("100"), DailyFoodSnapshotUnit.GRAM),
				nutrientsPerBasis = DailyNutritionValues(
					caloriesKcal = BigDecimal("62"),
					proteinGrams = BigDecimal("9.5"),
					carbohydratesGrams = BigDecimal("4.1"),
					fatGrams = BigDecimal("0.2"),
					fiberGrams = BigDecimal("1"),
					sugarsGrams = BigDecimal("4.1"),
					saturatedFatGrams = BigDecimal("0.1"),
					sodiumMilligrams = BigDecimal("40"),
					saltGrams = BigDecimal("0.1"),
				),
				defaultServing = DailyFoodDefaultServingSnapshot(BigDecimal("170"), DailyFoodSnapshotUnit.GRAM),
				conversions = DailyFoodConversionSnapshot(
					gramsPerPiece = BigDecimal("12"),
					gramsPerServing = BigDecimal("170"),
				),
				nutritionSource = DailyNutritionSourceSnapshot(
					type = DailyFoodItemSourceType.USER_FOOD,
					originalSourceType = "PRODUCT_LABEL",
					estimated = false,
				),
				version = 3,
				updatedAt = Instant.parse("2026-07-31T09:00:00Z"),
			)
		}
	}

	@SpringBootConfiguration
	@EnableAutoConfiguration(
		excludeName = [
			"org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration",
			"org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration",
			"org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration",
		],
	)
	@Import(
		SecurityConfig::class,
		OpenApiConfig::class,
		RestAuthenticationEntryPoint::class,
		RestAccessDeniedHandler::class,
		FirebaseAuthenticationFilter::class,
		SecurityCurrentUserProvider::class,
		DailyController::class,
		DailyApiExceptionHandler::class,
		DailyCaptureContentFactory::class,
		DailyCaptureService::class,
		DailyManualCaptureService::class,
		CaptureConfirmationService::class,
		DailyCaptureEditService::class,
		DailyMetricsProjectionService::class,
		DailyFinalizationService::class,
		DailyDayReopeningService::class,
		DailyQueryService::class,
		TestBeans::class,
	)
	class TestApplication

	@TestConfiguration(proxyBeanMethods = false)
	class TestBeans {
		@Bean
		fun tokenVerifier(): FirebaseTokenVerifier = FirebaseTokenVerifier { token ->
			if (token != "valid-a" && token != "valid-b") {
				throw FirebaseTokenVerificationException()
			}
			FirebaseTokenClaims(
				issuer = "https://securetoken.google.com/fitlake-test",
				subject = "firebase-uid-$token",
				email = "$token@example.com",
				emailVerified = true,
				displayName = token,
			)
		}

		@Bean fun userAccounts() = InMemoryUserAccountRepository()
		@Bean fun authIdentities() = InMemoryUserAuthIdentityRepository()
		@Bean fun dailyDays() = InMemoryDailyDayRepository()
		@Bean fun dailyCaptures() = InMemoryDailyCaptureRepository()
		@Bean fun dailyCaptureAudits() = InMemoryDailyCaptureAuditRepository()
		@Bean fun dailyMetrics() = InMemoryDailyMetricsRepository()
		@Bean fun dailyUserFoodLookup() = TestUserFoodLookup()
		@Bean fun clock(): Clock = Clock.fixed(Instant.parse("2026-07-28T10:00:00Z"), ZoneId.of("UTC"))

		@Bean
		fun userProvisioningService(
			userAccounts: InMemoryUserAccountRepository,
			authIdentities: InMemoryUserAuthIdentityRepository,
			clock: Clock,
		) = UserProvisioningService(
			userAccountRepository = userAccounts,
			userAuthIdentityRepository = authIdentities,
			transactionExecutor = ImmediateTransactionExecutor,
			clock = clock,
			defaultUserTimezone = ZoneId.of("Europe/Rome"),
		)

		@Bean
		fun transactionExecutor() = ImmediateTransactionExecutor
	}
}
