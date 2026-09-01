package com.fitlake.shared.infrastructure.http

import com.fitlake.shared.application.elapsedMilliseconds
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import org.springframework.web.servlet.HandlerMapping
import java.util.UUID

/**
 * Establishes one privacy-safe correlation ID for the full synchronous HTTP request.
 * It deliberately does not cache or inspect request/response bodies.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
class RequestCorrelationFilter : OncePerRequestFilter() {
	override fun doFilterInternal(
		request: HttpServletRequest,
		response: HttpServletResponse,
		filterChain: FilterChain,
	) {
		val requestId = resolveRequestId(request.getHeader(REQUEST_ID_HEADER))
		val startedAtNanos = System.nanoTime()
		var failure: Throwable? = null
		response.setHeader(REQUEST_ID_HEADER, requestId)
		request.setAttribute(REQUEST_ID_ATTRIBUTE, requestId)

		MDC.putCloseable(REQUEST_ID_MDC_KEY, requestId).use {
			try {
				filterChain.doFilter(request, response)
			} catch (exception: Throwable) {
				failure = exception
				throw exception
			} finally {
				logCompletion(request, response, startedAtNanos, failure)
			}
		}
	}

	private fun logCompletion(
		request: HttpServletRequest,
		response: HttpServletResponse,
		startedAtNanos: Long,
		failure: Throwable?,
	) {
		val status = if (failure != null && response.status < 500) 500 else response.status
		val builder = if (isNoisyRequest(request)) eventLogger.atDebug() else eventLogger.atInfo()
		builder
			.addKeyValue("event", "http_request_completed")
			.addKeyValue("outcome", httpOutcome(status))
			.addKeyValue("method", request.method)
			.addKeyValue("route", safeRoute(request))
			.addKeyValue("status", status)
			.addKeyValue("durationMs", elapsedMilliseconds(startedAtNanos))
			.log("HTTP request completed")
	}

	private fun safeRoute(request: HttpServletRequest): String {
		request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE)?.toString()?.let { return it }
		val path = request.requestURI
		return when {
			path == "/actuator/health" || path.startsWith("/actuator/health/") -> "/actuator/health/**"
			path.startsWith("/v3/api-docs") -> "/v3/api-docs/**"
			path == "/swagger-ui.html" || path.startsWith("/swagger-ui/") -> "/swagger-ui/**"
			path.startsWith("/api/") -> "/api/**"
			path == "/favicon.ico" -> "/favicon.ico"
			else -> "unmatched"
		}
	}

	private fun isNoisyRequest(request: HttpServletRequest): Boolean =
		request.requestURI == "/favicon.ico" ||
			request.requestURI == "/actuator/health" ||
			request.requestURI.startsWith("/actuator/health/")

	private fun httpOutcome(status: Int): String = when (status) {
		in 100..399 -> "success"
		in 400..499 -> "rejected"
		else -> "failure"
	}

	private fun resolveRequestId(candidate: String?): String =
		candidate?.takeIf(REQUEST_ID_PATTERN::matches) ?: UUID.randomUUID().toString()

	companion object {
		const val REQUEST_ID_HEADER = "X-Request-Id"
		const val REQUEST_ID_MDC_KEY = "requestId"
		const val REQUEST_ID_ATTRIBUTE = "com.fitlake.requestId"
		private val REQUEST_ID_PATTERN = Regex("^[A-Za-z0-9._-]{1,100}$")
		private val eventLogger = LoggerFactory.getLogger(RequestCorrelationFilter::class.java)
	}
}
