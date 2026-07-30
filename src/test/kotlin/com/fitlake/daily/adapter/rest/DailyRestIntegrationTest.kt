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
import com.fitlake.daily.application.capture.DailyCaptureEditService
import com.fitlake.daily.application.capture.DailyCaptureService
import com.fitlake.daily.application.capture.DailyPayloadFactory
import com.fitlake.daily.application.finalization.DailyFinalizationService
import com.fitlake.daily.application.finalization.DailyMetricsProjectionService
import com.fitlake.support.ImmediateTransactionExecutor
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
			jsonPath("$.payload.fields.bodyWeightKg") { value(78.4) }
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
	fun `precise REST edits rejection and soft delete preserve expected state`() {
		val created = mockMvc.post("/api/daily/days/2026-08-05/captures") {
			auth("valid-a")
			contentType = MediaType.APPLICATION_JSON
			content = foodCaptureBody(quantity = 40, unit = "g")
		}.andExpect {
			status { isCreated() }
			jsonPath("$.payload.meals[0].items[0].itemTempId") { value("oats") }
		}
		val captureId = captureId(created.andReturn().response.contentAsString)

		mockMvc.patch("/api/daily/captures/$captureId/food-items/oats") {
			auth("valid-a")
			contentType = MediaType.APPLICATION_JSON
			content = """{"quantity": 50, "unit": "grammi"}"""
		}.andExpect {
			status { isOk() }
			jsonPath("$.payload.meals[0].items[0].quantity") { value(50) }
			jsonPath("$.payload.meals[0].items[0].unit") { value("g") }
		}

		mockMvc.put("/api/daily/captures/$captureId") {
			auth("valid-a")
			contentType = MediaType.APPLICATION_JSON
			content = foodCaptureBody(quantity = 60, unit = "g")
		}.andExpect {
			status { isOk() }
			jsonPath("$.payload.meals[0].items[0].quantity") { value(60) }
		}

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
	fun `invalid food unit returns validation error`() {
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
			jsonPath("$.error") { value("validation_error") }
		}
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
	fun `OpenAPI exposes Daily REST operations`() {
		mockMvc.get("/v3/api-docs")
			.andExpect {
				status { isOk() }
				jsonPath("$.paths['/api/daily/days/{date}/captures']") { exists() }
				jsonPath("$.paths['/api/daily/days/{date}/finalize']") { exists() }
			}
	}

	private fun createFieldsCapture(date: String, token: String) =
		mockMvc.post("/api/daily/days/$date/captures") {
			auth(token)
			contentType = MediaType.APPLICATION_JSON
			content = """
				{
				  "type": "DAILY_FIELDS",
				  "fields": {
				    "bodyWeightKg": 78.4,
				    "sleepHours": 7.5
				  }
				}
			""".trimIndent()
		}

	private fun foodCaptureBody(quantity: Int, unit: String) = """
		{
		  "type": "FOOD",
		  "meals": [{
		    "mealTempId": "breakfast",
		    "mealName": "colazione",
		    "items": [{
		      "itemTempId": "oats",
		      "foodName": "avena",
		      "quantity": $quantity,
		      "unit": "$unit"
		    }]
		  }]
		}
	""".trimIndent()

	private fun captureId(response: String): String =
		requireNotNull(Regex("\"captureId\":\"([^\"]+)\"").find(response)?.groupValues?.get(1))

	private fun MockHttpServletRequestDsl.auth(token: String) {
		header("Authorization", "Bearer $token")
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
		DailyPayloadFactory::class,
		DailyCaptureService::class,
		CaptureConfirmationService::class,
		DailyCaptureEditService::class,
		DailyMetricsProjectionService::class,
		DailyFinalizationService::class,
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
		@Bean fun dailyMetrics() = InMemoryDailyMetricsRepository()
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
