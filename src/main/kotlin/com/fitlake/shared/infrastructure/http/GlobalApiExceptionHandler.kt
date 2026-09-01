package com.fitlake.shared.infrastructure.http

import com.fitlake.shared.logging.sanitizedForTechnicalLogging
import org.slf4j.LoggerFactory
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.HttpStatusCode
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.context.request.WebRequest
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler

data class InternalApiError(
	val error: String,
	val message: String,
	val fieldErrors: Map<String, String> = emptyMap(),
)

/** Logs an unexpected MVC failure once, at the outer application boundary. */
@Order(Ordered.LOWEST_PRECEDENCE)
@RestControllerAdvice
class GlobalApiExceptionHandler : ResponseEntityExceptionHandler() {
	override fun handleExceptionInternal(
		exception: Exception,
		body: Any?,
		headers: HttpHeaders,
		statusCode: HttpStatusCode,
		request: WebRequest,
	): ResponseEntity<Any> {
		if (statusCode.is5xxServerError) {
			logUnexpected(exception, "FRAMEWORK_HTTP_ERROR")
		}
		val status = statusCode.value()
		val safeBody = if (statusCode.is5xxServerError) {
			InternalApiError("internal_server_error", "The request could not be completed")
		} else {
			InternalApiError(httpErrorCode(status), safeHttpMessage(status))
		}
		return ResponseEntity.status(statusCode).headers(headers).body(safeBody)
	}

	@ExceptionHandler(Exception::class)
	fun unexpected(exception: Exception): ResponseEntity<InternalApiError> {
		logUnexpected(exception, "INTERNAL_ERROR")
		return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(
			InternalApiError(
				error = "internal_server_error",
				message = "The request could not be completed",
			),
		)
	}

	private fun logUnexpected(exception: Exception, errorCode: String) {
		eventLogger.atError()
			.addKeyValue("event", "unhandled_exception")
			.addKeyValue("outcome", "failure")
			.addKeyValue("errorCode", errorCode)
			.addKeyValue("exceptionType", exception.javaClass.name)
			.setCause(exception.sanitizedForTechnicalLogging())
			.log("Unhandled request failure")
	}

	private fun httpErrorCode(status: Int): String = when (status) {
		400 -> "invalid_request"
		404 -> "not_found"
		405 -> "method_not_allowed"
		406 -> "not_acceptable"
		413 -> "payload_too_large"
		415 -> "unsupported_media_type"
		else -> "request_rejected"
	}

	private fun safeHttpMessage(status: Int): String = when (status) {
		400 -> "The request is missing or invalid"
		404 -> "The requested resource was not found"
		405 -> "The request method is not supported"
		406 -> "The requested response type is not available"
		413 -> "The request payload is too large"
		415 -> "The request content type is not supported"
		else -> "The request was rejected"
	}

	private companion object {
		val eventLogger = LoggerFactory.getLogger(GlobalApiExceptionHandler::class.java)
	}
}
