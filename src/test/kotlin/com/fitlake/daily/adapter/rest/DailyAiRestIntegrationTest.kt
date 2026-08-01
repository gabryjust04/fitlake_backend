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
import com.fitlake.daily.application.ai.AiCaptureProposal
import com.fitlake.daily.application.ai.AiFoodItemProposal
import com.fitlake.daily.application.ai.AiMealProposal
import com.fitlake.daily.application.ai.DailyAiAuditService
import com.fitlake.daily.application.ai.DailyAiCaptureProposalFactory
import com.fitlake.daily.application.ai.DailyAiMessageService
import com.fitlake.daily.application.ai.DailyAiProviderUnavailableException
import com.fitlake.daily.application.ai.DailyAiTerminalService
import com.fitlake.daily.application.capture.DailyCaptureService
import com.fitlake.daily.application.port.DailyAiUserFoodMatchPort
import com.fitlake.daily.application.port.DailyAiUserFoodMatchResult
import com.fitlake.daily.domain.capture.DailyCaptureId
import com.fitlake.daily.domain.capture.DailyCaptureStatus
import com.fitlake.support.DailyAiScript
import com.fitlake.support.ImmediateTransactionExecutor
import com.fitlake.support.InMemoryAiInterpretationLogRepository
import com.fitlake.support.InMemoryDailyCaptureRepository
import com.fitlake.support.InMemoryDailyDayRepository
import com.fitlake.support.InMemoryDailyInboxEventRepository
import com.fitlake.support.InMemoryUserAccountRepository
import com.fitlake.support.InMemoryUserAuthIdentityRepository
import com.fitlake.support.ScriptedDailyAiInterpreter
import com.fitlake.user.application.UserProvisioningService
import com.fitlake.user.application.UserQueryService
import org.junit.jupiter.api.BeforeEach
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
import org.springframework.test.web.servlet.MockHttpServletRequestDsl
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import kotlin.test.assertEquals

@SpringBootTest(
	classes = [DailyAiRestIntegrationTest.TestApplication::class],
	properties = [
		"OPENAI_API_KEY=test-only",
		"FIREBASE_PROJECT_ID=fitlake-test",
		"management.endpoints.web.exposure.include=health",
		"spring.ai.model.chat=none",
	],
)
@AutoConfigureMockMvc
class DailyAiRestIntegrationTest @Autowired constructor(
	private val mockMvc: MockMvc,
	private val interpreter: ScriptedDailyAiInterpreter,
	private val days: InMemoryDailyDayRepository,
	private val captures: InMemoryDailyCaptureRepository,
	private val inboxEvents: InMemoryDailyInboxEventRepository,
	private val interpretationLogs: InMemoryAiInterpretationLogRepository,
	private val userAccounts: InMemoryUserAccountRepository,
	private val authIdentities: InMemoryUserAuthIdentityRepository,
) {
	private val now = Instant.parse("2026-07-30T08:00:00Z")

	@BeforeEach
	fun resetState() {
		interpreter.reset()
		days.clear()
		captures.clear()
		inboxEvents.clear()
		interpretationLogs.clear()
		userAccounts.clear()
		authIdentities.clear()
	}

	@Test
	fun `message endpoint creates an open capture and idempotently replays it`() {
		interpreter.script(DailyAiScript.CreateCapture(validFoodProposal()))

		val first = postMessage(
			date = "2026-07-30",
			key = "rest-create-1",
			text = "A colazione ho mangiato 40 grammi di avena",
		).andExpect {
			status { isCreated() }
			jsonPath("$.outcome") { value("CAPTURE_CREATED") }
			jsonPath("$.capture.date") { value("2026-07-30") }
			jsonPath("$.capture.status") { value("OPEN") }
			jsonPath("$.capture.createdBy") { value("AI") }
			jsonPath("$.capture.type") { value("FOOD") }
			jsonPath("$.capture.payload.entries[0].items[0].displayName") { value("avena") }
			jsonPath("$.capture.payload.entries[0].items[0].itemId") { isNotEmpty() }
			jsonPath("$.capture.payload.schemaVersion") { value(2) }
			jsonPath("$.capture.payload.entries[0].items[0].sourceType") { value("AI_ESTIMATE") }
			jsonPath("$.capture.payload.entries[0].items[0].userFoodId") { doesNotExist() }
			jsonPath("$.capture.payload.entries[0].items[0].calculatedNutrition.caloriesKcal") { value(150) }
			jsonPath("$.capture.payload.entries[0].items[0].calculatedNutrition.proteinGrams") { value(5) }
			jsonPath("$.capture.payload.entries[0].items[0].calculatedNutrition.carbohydratesGrams") { value(27) }
			jsonPath("$.capture.payload.entries[0].items[0].calculatedNutrition.fatGrams") { value(3) }
		}
		val captureId = captureId(first.andReturn().response.contentAsString)

		postMessage(
			date = "2026-07-30",
			key = "rest-create-1",
			text = "A colazione ho mangiato 40 grammi di avena",
		).andExpect {
			status { isCreated() }
			jsonPath("$.capture.captureId") { value(captureId) }
		}

		assertEquals(1, interpreter.callCount)
		assertEquals(1, captures.count())
		assertEquals(1, inboxEvents.count())
		assertEquals(1, interpretationLogs.count())
	}

	@Test
	fun `message endpoint returns clarification without a capture`() {
		interpreter.script(DailyAiScript.AskClarification("Quanti grammi di riso?"))

		postMessage("2026-07-31", "rest-clarification-1", "Ho mangiato riso").andExpect {
			status { isOk() }
			jsonPath("$.outcome") { value("CLARIFICATION_REQUIRED") }
			jsonPath("$.question") { value("Quanti grammi di riso?") }
			jsonPath("$.capture") { isEmpty() }
		}

		assertEquals(0, captures.count())
	}

	@Test
	fun `message endpoint returns no op without a capture`() {
		interpreter.script(DailyAiScript.NoOp("Nessun dato Daily"))

		postMessage("2026-08-01", "rest-noop-1", "Ciao").andExpect {
			status { isOk() }
			jsonPath("$.outcome") { value("NO_OP") }
			jsonPath("$.reason") { value("Nessun dato Daily") }
			jsonPath("$.capture") { isEmpty() }
		}

		assertEquals(0, captures.count())
	}

	@Test
	fun `message endpoint requires Firebase authentication`() {
		mockMvc.post("/api/daily/days/2026-08-02/messages") {
			header("Idempotency-Key", "unauthenticated-1")
			contentType = MediaType.APPLICATION_JSON
			content = """{"text":"avena 40 g"}"""
		}.andExpect {
			status { isUnauthorized() }
			jsonPath("$.error") { value("unauthorized") }
		}

		assertEquals(0, interpreter.callCount)
	}

	@Test
	fun `message endpoint validates body header and ISO date before AI`() {
		postMessage("2026-08-03", "blank-text-1", "   ").andExpect {
			status { isBadRequest() }
			jsonPath("$.error") { value("validation_error") }
		}

		mockMvc.post("/api/daily/days/2026-08-03/messages") {
			auth("valid-a")
			contentType = MediaType.APPLICATION_JSON
			content = """{"text":"avena 40 g"}"""
		}.andExpect {
			status { isBadRequest() }
			jsonPath("$.error") { value("invalid_request") }
		}

		postMessage("not-a-date", "invalid-date-1", "avena 40 g").andExpect {
			status { isBadRequest() }
			jsonPath("$.error") { value("invalid_request") }
		}

		postMessage("2026-08-03", "long-text-1", "x".repeat(4001)).andExpect {
			status { isBadRequest() }
			jsonPath("$.error") { value("validation_error") }
		}

		assertEquals(0, interpreter.callCount)
		assertEquals(0, inboxEvents.count())
	}

	@Test
	fun `provider failure is sanitized and leaves no capture`() {
		interpreter.script(
			DailyAiScript.Fail(DailyAiProviderUnavailableException(IllegalStateException("api-key=secret"))),
		)

		postMessage("2026-08-04", "provider-failure-1", "avena 40 g").andExpect {
			status { isServiceUnavailable() }
			jsonPath("$.error") { value("ai_provider_unavailable") }
			jsonPath("$.message") { value("The AI provider is unavailable") }
			content { string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("secret"))) }
		}

		assertEquals(0, captures.count())
	}

	@Test
	fun `reprocess endpoint creates a new capture and replaces the old proposal`() {
		interpreter.script(DailyAiScript.CreateCapture(validFoodProposal()))
		val original = postMessage("2026-08-05", "original-1", "avena 40 g")
		val oldId = captureId(original.andReturn().response.contentAsString)
		interpreter.script(DailyAiScript.CreateCapture(validFoodProposal("biscotti")))

		val replacement = mockMvc.post("/api/daily/captures/$oldId/reprocess") {
			auth("valid-a")
			header("Idempotency-Key", "replacement-1")
			contentType = MediaType.APPLICATION_JSON
			content = """{"text":"A colazione ho mangiato avena e tre biscotti"}"""
		}.andExpect {
			status { isCreated() }
			jsonPath("$.outcome") { value("CAPTURE_REPLACED") }
			jsonPath("$.replacedCaptureId") { value(oldId) }
			jsonPath("$.capture.captureId") { value(org.hamcrest.Matchers.not(oldId)) }
			jsonPath("$.capture.status") { value("OPEN") }
			jsonPath("$.capture.payload.entries[0].items[0].displayName") { value("biscotti") }
		}
		val newId = captureId(replacement.andReturn().response.contentAsString)

		assertEquals(DailyCaptureStatus.REJECTED, captures.findById(DailyCaptureId(UUID.fromString(oldId)))?.status)
		assertEquals(DailyCaptureStatus.OPEN, captures.findById(DailyCaptureId(UUID.fromString(newId)))?.status)
		assertEquals(2, captures.count())
	}

	@Test
	fun `clarification on reprocess preserves the old open proposal`() {
		interpreter.script(DailyAiScript.CreateCapture(validFoodProposal()))
		val original = postMessage("2026-08-06", "original-clarification", "avena 40 g")
		val oldId = captureId(original.andReturn().response.contentAsString)
		interpreter.script(DailyAiScript.AskClarification("Quanti biscotti?"))

		mockMvc.post("/api/daily/captures/$oldId/reprocess") {
			auth("valid-a")
			header("Idempotency-Key", "replacement-clarification")
			contentType = MediaType.APPLICATION_JSON
			content = """{"text":"A colazione ho mangiato dei biscotti"}"""
		}.andExpect {
			status { isOk() }
			jsonPath("$.outcome") { value("CLARIFICATION_REQUIRED") }
		}

		assertEquals(DailyCaptureStatus.OPEN, captures.findById(DailyCaptureId(UUID.fromString(oldId)))?.status)
		assertEquals(1, captures.count())
	}

	@Test
	fun `accepted and foreign captures cannot be reprocessed`() {
		interpreter.script(DailyAiScript.CreateCapture(validFoodProposal()))
		val acceptedResponse = postMessage("2026-08-07", "original-accepted", "avena 40 g")
		val acceptedId = captureId(acceptedResponse.andReturn().response.contentAsString)
		val acceptedCaptureId = DailyCaptureId(UUID.fromString(acceptedId))
		val accepted = requireNotNull(captures.findById(acceptedCaptureId)).accept(now.plusSeconds(1))
		captures.save(accepted)
		interpreter.reset(DailyAiScript.CreateCapture(validFoodProposal("biscotti")))

		mockMvc.post("/api/daily/captures/$acceptedId/reprocess") {
			auth("valid-a")
			header("Idempotency-Key", "accepted-reprocess")
			contentType = MediaType.APPLICATION_JSON
			content = """{"text":"testo completo"}"""
		}.andExpect {
			status { isConflict() }
		}

		val foreignResponse = postMessage("2026-08-08", "original-foreign", "avena 40 g", "valid-a")
		val foreignId = captureId(foreignResponse.andReturn().response.contentAsString)
		interpreter.reset(DailyAiScript.CreateCapture(validFoodProposal("biscotti")))
		mockMvc.post("/api/daily/captures/$foreignId/reprocess") {
			auth("valid-b")
			header("Idempotency-Key", "foreign-reprocess")
			contentType = MediaType.APPLICATION_JSON
			content = """{"text":"testo completo"}"""
		}.andExpect {
			status { isNotFound() }
		}

		assertEquals(0, interpreter.callCount)
	}

	@Test
	fun `OpenAPI exposes both Daily AI routes and idempotency headers`() {
		mockMvc.get("/v3/api-docs")
			.andExpect {
				status { isOk() }
				jsonPath("$.paths['/api/daily/days/{date}/messages']") { exists() }
				jsonPath("$.paths['/api/daily/captures/{captureId}/reprocess']") { exists() }
				jsonPath("$.paths['/api/daily/days/{date}/messages'].post.parameters[1].name") {
					value("Idempotency-Key")
				}
			}
	}

	private fun postMessage(
		date: String,
		key: String,
		text: String,
		token: String = "valid-a",
	) = mockMvc.post("/api/daily/days/$date/messages") {
		auth(token)
		header("Idempotency-Key", key)
		contentType = MediaType.APPLICATION_JSON
		content = """{"text":"$text"}"""
	}

	private fun validFoodProposal(foodName: String = "avena") = AiCaptureProposal(
		type = "FOOD",
		meals = listOf(
			AiMealProposal(
				mealName = "colazione",
				items = listOf(
					AiFoodItemProposal(
						foodName = foodName,
						quantity = BigDecimal("40"),
						unit = "g",
						calories = BigDecimal("150"),
						proteinG = BigDecimal("5"),
						carbsG = BigDecimal("27"),
						fatG = BigDecimal("3"),
					),
				),
			),
		),
	)

	private fun captureId(response: String): String =
		requireNotNull(Regex("\\\"captureId\\\":\\\"([^\\\"]+)\\\"").find(response)?.groupValues?.get(1))

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
		DailyAiController::class,
		DailyApiExceptionHandler::class,
		DailyCaptureService::class,
		DailyAiAuditService::class,
		DailyAiTerminalService::class,
		DailyAiMessageService::class,
		UserQueryService::class,
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
		@Bean fun dailyInboxEvents() = InMemoryDailyInboxEventRepository()
		@Bean fun aiInterpretationLogs() = InMemoryAiInterpretationLogRepository()
		@Bean fun clock(): Clock = Clock.fixed(Instant.parse("2026-07-30T08:00:00Z"), ZoneId.of("UTC"))

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
		fun interpreter(terminalService: DailyAiTerminalService) = ScriptedDailyAiInterpreter(terminalService)

		@Bean
		fun dailyAiCaptureProposalFactory() = DailyAiCaptureProposalFactory(
			DailyAiUserFoodMatchPort { _, _ -> DailyAiUserFoodMatchResult.None },
		)

		@Bean
		fun transactionExecutor() = ImmediateTransactionExecutor
	}
}
