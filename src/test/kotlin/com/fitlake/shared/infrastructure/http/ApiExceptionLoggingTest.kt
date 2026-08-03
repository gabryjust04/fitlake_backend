package com.fitlake.shared.infrastructure.http

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.classic.spi.IThrowableProxy
import com.fitlake.daily.adapter.rest.DailyApiExceptionHandler
import com.fitlake.daily.adapter.rest.DailyAiController
import com.fitlake.daily.adapter.rest.DailyController
import com.fitlake.daily.application.DailyStateCorruptionException
import com.fitlake.daily.application.DailyValidationException
import com.fitlake.food.adapter.rest.UserFoodApiExceptionHandler
import com.fitlake.food.application.UserFoodPersistenceException
import com.fitlake.support.LogEventCapture
import com.fitlake.support.structuredFields
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.slf4j.MDC
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.dao.OptimisticLockingFailureException
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.bind.annotation.RestControllerAdvice
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ApiExceptionLoggingTest {
	@AfterEach
	fun clearMdc() {
		MDC.clear()
	}

	@Test
	fun `unexpected MVC exception is logged once with safe structured metadata`() {
		val mockMvc = MockMvcBuilders
			.standaloneSetup(FailingController())
			.setControllerAdvice(GlobalApiExceptionHandler())
			.build()

		LogEventCapture(GlobalApiExceptionHandler::class.java).use { capture ->
			MDC.put("requestId", "request-test-123")
			mockMvc.get("/api/test/failure")
				.andExpect {
					status { isInternalServerError() }
					content { contentTypeCompatibleWith(MediaType.APPLICATION_JSON) }
					jsonPath("$.error") { value("internal_server_error") }
					jsonPath("$.message") { value("The request could not be completed") }
					jsonPath("$.fieldErrors") { exists() }
					jsonPath("$.stackTrace") { doesNotExist() }
				}

			val events = capture.events.filter { it.structuredFields()["event"] == "unhandled_exception" }
			assertEquals(1, events.size)
			val event = events.single()
			assertEquals(Level.ERROR, event.level)
			assertEquals("failure", event.structuredFields()["outcome"])
			assertEquals("INTERNAL_ERROR", event.structuredFields()["errorCode"])
			assertEquals(IllegalStateException::class.java.name, event.structuredFields()["exceptionType"])
			assertEquals("request-test-123", event.mdcPropertyMap["requestId"])
			assertNotNull(event.throwableProxy)
			assertDoesNotContain(event, PRIVATE_EXCEPTION_DETAIL)
		}
	}

	@Test
	fun `expected framework HTTP errors preserve safe 4xx responses without error logs`() {
		val mockMvc = MockMvcBuilders
			.standaloneSetup(FailingController())
			.setControllerAdvice(GlobalApiExceptionHandler())
			.build()

		LogEventCapture(GlobalApiExceptionHandler::class.java).use { capture ->
			mockMvc.get("/missing-private-resource-42")
				.andExpect { status { isNotFound() } }
			mockMvc.post("/api/test/failure")
				.andExpect {
					status { isMethodNotAllowed() }
					jsonPath("$.error") { value("method_not_allowed") }
				}
			mockMvc.post("/api/test/json") {
				contentType = MediaType.TEXT_PLAIN
				content = "private request content"
			}.andExpect {
				status { isUnsupportedMediaType() }
				jsonPath("$.error") { value("unsupported_media_type") }
			}

			assertTrue(capture.events.none { it.level == Level.ERROR })
		}
	}

	@Test
	fun `known Daily corruption is logged once while expected errors are not logged as unexpected`() {
		val handler = DailyApiExceptionHandler()
		LogEventCapture(DailyApiExceptionHandler::class.java).use { capture ->
			val response = handler.corruptedState(DailyStateCorruptionException(PRIVATE_DAILY_DETAIL))

			assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.statusCode)
			assertEquals("internal_server_error", response.body?.error)
			assertEquals("Daily state is inconsistent", response.body?.message)

			val events = capture.events.filter {
				it.structuredFields()["event"] == "daily_state_corruption_detected"
			}
			assertEquals(1, events.size)
			val event = events.single()
			assertEquals(Level.ERROR, event.level)
			assertEquals("failure", event.structuredFields()["outcome"])
			assertEquals("DAILY_STATE_CORRUPTION", event.structuredFields()["errorCode"])
			assertNotNull(event.throwableProxy)
			assertDoesNotContain(event, PRIVATE_DAILY_DETAIL)

			handler.validation(DailyValidationException("Expected validation failure"))
			handler.aiInternalFailure()
			assertEquals(1, capture.events.size, "Expected and already-owned AI failures must not add another ERROR")
		}
	}

	@Test
	fun `optimistic conflict response does not expose persistence details`() {
		val privateDetail = "private row identifier and SQL detail"
		val response = DailyApiExceptionHandler().conflict(
			OptimisticLockingFailureException(privateDetail),
		)

		assertEquals(HttpStatus.CONFLICT, response.statusCode)
		assertEquals("conflict", response.body?.error)
		assertEquals("Daily state conflict", response.body?.message)
		assertFalse(response.body.toString().contains(privateDetail))
	}

	@Test
	fun `user food persistence failure is logged once without database exception detail`() {
		val handler = UserFoodApiExceptionHandler()
		LogEventCapture(UserFoodApiExceptionHandler::class.java).use { capture ->
			val response = handler.persistenceFailure(
				UserFoodPersistenceException(IllegalStateException(PRIVATE_DATABASE_DETAIL)),
			)

			assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.statusCode)
			assertEquals("internal_server_error", response.body?.error)
			val event = capture.events.single()
			assertEquals(Level.ERROR, event.level)
			assertEquals("user_food_persistence_failed", event.structuredFields()["event"])
			assertEquals("USER_FOOD_PERSISTENCE_ERROR", event.structuredFields()["errorCode"])
			assertNotNull(event.throwableProxy)
			assertDoesNotContain(event, PRIVATE_DATABASE_DETAIL)
		}
	}

	@Test
	fun `module advice has precedence over the global fallback and Daily advice is scoped`() {
		assertEquals(
			Ordered.HIGHEST_PRECEDENCE,
			DailyApiExceptionHandler::class.java.getAnnotation(Order::class.java).value,
		)
		assertEquals(
			Ordered.HIGHEST_PRECEDENCE,
			UserFoodApiExceptionHandler::class.java.getAnnotation(Order::class.java).value,
		)
		assertEquals(
			Ordered.LOWEST_PRECEDENCE,
			GlobalApiExceptionHandler::class.java.getAnnotation(Order::class.java).value,
		)

		val advice = DailyApiExceptionHandler::class.java.getAnnotation(RestControllerAdvice::class.java)
		assertNotNull(advice)
		assertEquals(
			setOf(DailyController::class, DailyAiController::class),
			advice.assignableTypes.toSet(),
		)
	}

	private fun assertDoesNotContain(event: ILoggingEvent, prohibited: String) {
		val observableLogData = buildList {
			add(event.formattedMessage)
			event.argumentArray?.forEach { add(it.toString()) }
			event.keyValuePairs.forEach { add("${it.key}=${it.value}") }
			event.mdcPropertyMap.forEach { (key, value) -> add("$key=$value") }
			add(throwableText(event.throwableProxy))
		}.joinToString("\n")
		assertFalse(observableLogData.contains(prohibited), "Sensitive exception detail reached the log event")
	}

	private fun throwableText(proxy: IThrowableProxy?): String {
		if (proxy == null) return ""
		return buildString {
			append(proxy.className)
			append(proxy.message)
			proxy.stackTraceElementProxyArray.forEach { append(it.toString()) }
			append(throwableText(proxy.cause))
		}
	}

	@RestController
	private class FailingController {
		@GetMapping("/api/test/failure")
		fun fail(): ResponseEntity<Void> = throw IllegalStateException(PRIVATE_EXCEPTION_DETAIL)

		@PostMapping("/api/test/json", consumes = [MediaType.APPLICATION_JSON_VALUE])
		fun json(@RequestBody body: Map<String, Any?>): ResponseEntity<Void> = ResponseEntity.noContent().build()
	}

	private companion object {
		const val PRIVATE_EXCEPTION_DETAIL = "PRIVATE_USER_AUTHORED_DETAIL_9f3c"
		const val PRIVATE_DAILY_DETAIL = "PRIVATE_WEIGHT_AND_NOTE_DETAIL_7a2e"
		const val PRIVATE_DATABASE_DETAIL = "PRIVATE_DATABASE_ROW_DETAIL_4c1a"
	}
}
