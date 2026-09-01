package com.fitlake.shared.infrastructure.http

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.web.servlet.HandlerMapping
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RequestCorrelationFilterTest {
	private val filter = RequestCorrelationFilter()
	private lateinit var logger: Logger
	private lateinit var appender: ListAppender<ILoggingEvent>
	private var previousLevel: Level? = null

	@BeforeEach
	fun attachAppender() {
		MDC.clear()
		logger = LoggerFactory.getLogger(RequestCorrelationFilter::class.java) as Logger
		previousLevel = logger.level
		logger.level = Level.DEBUG
		appender = ListAppender<ILoggingEvent>().also {
			it.start()
			logger.addAppender(it)
		}
	}

	@AfterEach
	fun detachAppender() {
		logger.detachAppender(appender)
		appender.stop()
		logger.level = previousLevel
		MDC.clear()
	}

	@Test
	fun `missing request id is generated exposed and available only during the request`() {
		val request = MockHttpServletRequest("GET", "/api/me")
		val response = MockHttpServletResponse()
		var requestIdDuringChain: String? = null

		execute(request, response) { _, _ ->
			requestIdDuringChain = MDC.get(RequestCorrelationFilter.REQUEST_ID_MDC_KEY)
		}

		val responseRequestId = response.getHeader(RequestCorrelationFilter.REQUEST_ID_HEADER)
		assertNotNull(responseRequestId)
		assertEquals(responseRequestId, requestIdDuringChain)
		assertEquals(responseRequestId, request.getAttribute(RequestCorrelationFilter.REQUEST_ID_ATTRIBUTE))
		assertEquals(responseRequestId, UUID.fromString(responseRequestId).toString())
		assertNull(MDC.get(RequestCorrelationFilter.REQUEST_ID_MDC_KEY))
	}

	@Test
	fun `valid id is preserved while an invalid id is replaced without leaking between requests`() {
		val firstRequest = MockHttpServletRequest("GET", "/api/me").apply {
			addHeader(RequestCorrelationFilter.REQUEST_ID_HEADER, "mobile.request-_42")
		}
		val firstResponse = MockHttpServletResponse()
		var firstMdcValue: String? = null
		execute(firstRequest, firstResponse) { _, _ ->
			firstMdcValue = MDC.get(RequestCorrelationFilter.REQUEST_ID_MDC_KEY)
		}

		assertEquals("mobile.request-_42", firstResponse.getHeader(RequestCorrelationFilter.REQUEST_ID_HEADER))
		assertEquals("mobile.request-_42", firstMdcValue)
		assertNull(MDC.get(RequestCorrelationFilter.REQUEST_ID_MDC_KEY))

		val invalid = "forged request id\r\nsecond-line"
		val secondRequest = MockHttpServletRequest("GET", "/api/me").apply {
			addHeader(RequestCorrelationFilter.REQUEST_ID_HEADER, invalid)
		}
		val secondResponse = MockHttpServletResponse()
		var secondMdcValue: String? = null
		execute(secondRequest, secondResponse) { _, _ ->
			secondMdcValue = MDC.get(RequestCorrelationFilter.REQUEST_ID_MDC_KEY)
		}

		val replacement = secondResponse.getHeader(RequestCorrelationFilter.REQUEST_ID_HEADER)
		assertNotNull(replacement)
		assertNotEquals(invalid, replacement)
		assertNotEquals(firstMdcValue, secondMdcValue)
		assertEquals(replacement, secondMdcValue)
		assertEquals(replacement, UUID.fromString(replacement).toString())
		assertNull(MDC.get(RequestCorrelationFilter.REQUEST_ID_MDC_KEY))
	}

	@Test
	fun `mdc is cleared and a failure completion is emitted when downstream throws`() {
		val request = MockHttpServletRequest("POST", "/api/daily/days/2026-08-03/finalize")
		val response = MockHttpServletResponse()

		assertFailsWith<IllegalStateException> {
			execute(request, response) { _, _ -> throw IllegalStateException("test-only failure") }
		}

		assertNull(MDC.get(RequestCorrelationFilter.REQUEST_ID_MDC_KEY))
		val event = completionEvents().single()
		assertEquals("failure", event.field("outcome"))
		assertEquals(500, event.field("status"))
	}

	@Test
	fun `completion event contains safe route metadata and excludes request content`() {
		val secretQuery = "private-query-value"
		val secretBody = "private-request-body"
		val bearer = "Bearer private-token-value"
		val concreteCaptureId = "ed8107d1-f2ec-4f36-bd02-2bd231788efd"
		val request = MockHttpServletRequest("PUT", "/api/daily/captures/$concreteCaptureId").apply {
			queryString = "query=$secretQuery"
			setContent(secretBody.toByteArray())
			addHeader("Authorization", bearer)
			addHeader(RequestCorrelationFilter.REQUEST_ID_HEADER, "safe-request-42")
		}
		val response = MockHttpServletResponse()

		execute(request, response) { servletRequest, servletResponse ->
			servletRequest.setAttribute(
				HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE,
				"/api/daily/captures/{captureId}",
			)
			servletResponse.status = 409
		}

		val event = completionEvents().single()
		assertEquals(Level.INFO, event.level)
		assertEquals("HTTP request completed", event.formattedMessage)
		assertEquals("http_request_completed", event.field("event"))
		assertEquals("rejected", event.field("outcome"))
		assertEquals("PUT", event.field("method"))
		assertEquals("/api/daily/captures/{captureId}", event.field("route"))
		assertEquals(409, event.field("status"))
		assertTrue((event.field("durationMs") as Long) >= 0)
		assertEquals("safe-request-42", event.mdcPropertyMap[RequestCorrelationFilter.REQUEST_ID_MDC_KEY])

		val serializedEvent = buildString {
			append(event.formattedMessage)
			event.keyValuePairs.forEach { pair -> append(pair.key).append('=').append(pair.value) }
			event.mdcPropertyMap.forEach { (key, value) -> append(key).append('=').append(value) }
		}
		assertFalse(serializedEvent.contains(secretQuery))
		assertFalse(serializedEvent.contains(secretBody))
		assertFalse(serializedEvent.contains(bearer))
		assertFalse(serializedEvent.contains(concreteCaptureId))
	}

	@Test
	fun `pre handler api request uses bounded fallback route`() {
		val privatePath = "/api/private-account-identifier-42"
		val privateToken = "Bearer private-security-token"
		val request = MockHttpServletRequest("GET", privatePath).apply {
			addHeader("Authorization", privateToken)
		}
		val response = MockHttpServletResponse()

		execute(request, response) { _, servletResponse -> servletResponse.status = 401 }

		val event = completionEvents().single()
		assertEquals("/api/**", event.field("route"))
		assertEquals("rejected", event.field("outcome"))
		val rendered = buildString {
			append(event.formattedMessage)
			event.keyValuePairs.forEach { append(it.key).append('=').append(it.value) }
		}
		assertFalse(rendered.contains(privatePath))
		assertFalse(rendered.contains(privateToken))
	}

	@Test
	fun `health and favicon completion events are debug and use bounded routes`() {
		val healthRequest = MockHttpServletRequest("GET", "/actuator/health/readiness")
		execute(healthRequest, MockHttpServletResponse()) { _, _ -> }
		val faviconRequest = MockHttpServletRequest("GET", "/favicon.ico")
		execute(faviconRequest, MockHttpServletResponse()) { _, _ -> }

		val events = completionEvents()
		assertEquals(2, events.size)
		assertEquals(Level.DEBUG, events[0].level)
		assertEquals("/actuator/health/**", events[0].field("route"))
		assertEquals(Level.DEBUG, events[1].level)
		assertEquals("/favicon.ico", events[1].field("route"))
	}

	private fun execute(
		request: MockHttpServletRequest,
		response: MockHttpServletResponse,
		action: (HttpServletRequest, HttpServletResponse) -> Unit,
	) {
		filter.doFilter(
			request,
			response,
			FilterChain { servletRequest, servletResponse ->
				action(servletRequest as HttpServletRequest, servletResponse as HttpServletResponse)
			},
		)
	}

	private fun completionEvents(): List<ILoggingEvent> = appender.list.filter {
		it.field("event") == "http_request_completed"
	}

	private fun ILoggingEvent.field(key: String): Any? = keyValuePairs
		.firstOrNull { pair -> pair.key == key }
		?.value
}
