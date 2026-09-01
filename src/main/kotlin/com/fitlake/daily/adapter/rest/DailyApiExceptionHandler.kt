package com.fitlake.daily.adapter.rest

import com.fitlake.daily.application.DailyConcurrentCreationException
import com.fitlake.daily.application.DailyConflictException
import com.fitlake.daily.application.DailyNotFoundException
import com.fitlake.daily.application.DailyStateCorruptionException
import com.fitlake.daily.application.DailyValidationException
import com.fitlake.daily.application.ai.DailyAiConfigurationException
import com.fitlake.daily.application.ai.DailyAiException
import com.fitlake.daily.application.ai.DailyAiIdempotencyConflictException
import com.fitlake.daily.application.ai.DailyAiInvalidOutputException
import com.fitlake.daily.application.ai.DailyAiOperationInProgressException
import com.fitlake.daily.application.ai.DailyAiPersistenceException
import com.fitlake.daily.application.ai.DailyAiProviderUnavailableException
import com.fitlake.daily.application.ai.DailyAiProviderAuthenticationException
import com.fitlake.daily.application.ai.DailyAiProviderQuotaException
import com.fitlake.daily.application.ai.DailyAiRateLimitException
import com.fitlake.daily.application.ai.DailyAiRecordedFailureException
import com.fitlake.daily.application.ai.DailyAiTimeoutException
import com.fitlake.shared.logging.sanitizedForTechnicalLogging
import jakarta.validation.ConstraintViolationException
import org.slf4j.LoggerFactory
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.dao.OptimisticLockingFailureException
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.web.bind.MissingRequestHeaderException
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.method.annotation.HandlerMethodValidationException
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException

data class DailyApiError(
	val error: String,
	val message: String,
	val fieldErrors: Map<String, String> = emptyMap(),
)

@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes = [DailyController::class, DailyAiController::class])
class DailyApiExceptionHandler {
	@ExceptionHandler(DailyNotFoundException::class)
	fun notFound(exception: DailyNotFoundException): ResponseEntity<DailyApiError> =
		response(HttpStatus.NOT_FOUND, "not_found", exception.message ?: "Daily resource was not found")

	@ExceptionHandler(
		DailyConflictException::class,
		DailyConcurrentCreationException::class,
		OptimisticLockingFailureException::class,
	)
	fun conflict(exception: RuntimeException): ResponseEntity<DailyApiError> {
		val safeMessage = when (exception) {
			is OptimisticLockingFailureException -> "Daily state conflict"
			else -> exception.message ?: "Daily state conflict"
		}
		return response(HttpStatus.CONFLICT, "conflict", safeMessage)
	}

	@ExceptionHandler(DailyValidationException::class)
	fun validation(exception: DailyValidationException): ResponseEntity<DailyApiError> =
		response(HttpStatus.BAD_REQUEST, "validation_error", exception.message ?: "Invalid daily payload")

	@ExceptionHandler(
		DailyAiIdempotencyConflictException::class,
		DailyAiOperationInProgressException::class,
	)
	fun aiConflict(exception: DailyAiException): ResponseEntity<DailyApiError> =
		response(HttpStatus.CONFLICT, exception.errorCode.lowercase(), exception.safeMessage)

	@ExceptionHandler(DailyAiInvalidOutputException::class)
	fun invalidAiOutput(exception: DailyAiInvalidOutputException): ResponseEntity<DailyApiError> =
		response(HttpStatus.BAD_GATEWAY, "ai_invalid_output", exception.safeMessage)

	@ExceptionHandler(
		DailyAiConfigurationException::class,
		DailyAiProviderAuthenticationException::class,
		DailyAiProviderQuotaException::class,
		DailyAiProviderUnavailableException::class,
	)
	fun aiUnavailable(exception: DailyAiException): ResponseEntity<DailyApiError> =
		response(HttpStatus.SERVICE_UNAVAILABLE, exception.errorCode.lowercase(), exception.safeMessage)

	@ExceptionHandler(DailyAiRateLimitException::class)
	fun aiRateLimited(exception: DailyAiRateLimitException): ResponseEntity<DailyApiError> =
		response(HttpStatus.TOO_MANY_REQUESTS, exception.errorCode.lowercase(), exception.safeMessage)

	@ExceptionHandler(DailyAiTimeoutException::class)
	fun aiTimeout(exception: DailyAiTimeoutException): ResponseEntity<DailyApiError> =
		response(HttpStatus.GATEWAY_TIMEOUT, "ai_timeout", exception.safeMessage)

	@ExceptionHandler(
		DailyAiPersistenceException::class,
		DailyAiRecordedFailureException::class,
	)
	fun aiInternalFailure(): ResponseEntity<DailyApiError> =
		response(HttpStatus.INTERNAL_SERVER_ERROR, "internal_server_error", "Daily AI processing failed")

	@ExceptionHandler(MethodArgumentNotValidException::class)
	fun beanValidation(exception: MethodArgumentNotValidException): ResponseEntity<DailyApiError> {
		val fields = exception.bindingResult.fieldErrors.associate { error ->
			error.field to (error.defaultMessage ?: "invalid value")
		}
		return ResponseEntity
			.status(HttpStatus.BAD_REQUEST)
			.body(DailyApiError("validation_error", "Request validation failed", fields))
	}

	@ExceptionHandler(HttpMessageNotReadableException::class)
	fun unreadableRequest(): ResponseEntity<DailyApiError> =
		response(HttpStatus.BAD_REQUEST, "invalid_request", "Request body is missing or malformed")

	@ExceptionHandler(
		MissingRequestHeaderException::class,
		ConstraintViolationException::class,
		HandlerMethodValidationException::class,
		MethodArgumentTypeMismatchException::class,
	)
	fun invalidRequest(): ResponseEntity<DailyApiError> =
		response(HttpStatus.BAD_REQUEST, "invalid_request", "Request parameters are missing or invalid")

	@ExceptionHandler(DailyStateCorruptionException::class)
	fun corruptedState(exception: DailyStateCorruptionException): ResponseEntity<DailyApiError> {
		logFailure(
			event = "daily_state_corruption_detected",
			errorCode = "DAILY_STATE_CORRUPTION",
			exception = exception,
			message = "Daily state corruption detected",
		)
		return response(HttpStatus.INTERNAL_SERVER_ERROR, "internal_server_error", "Daily state is inconsistent")
	}

	private fun logFailure(
		event: String,
		errorCode: String,
		exception: RuntimeException,
		message: String,
	) {
		logger.atError()
			.addKeyValue("event", event)
			.addKeyValue("outcome", "failure")
			.addKeyValue("errorCode", errorCode)
			.addKeyValue("exceptionType", exception.javaClass.name)
			.setCause(exception.sanitizedForTechnicalLogging())
			.log(message)
	}

	private fun response(status: HttpStatus, error: String, message: String): ResponseEntity<DailyApiError> =
		ResponseEntity.status(status).body(DailyApiError(error, message))

	private companion object {
		val logger = LoggerFactory.getLogger(DailyApiExceptionHandler::class.java)
	}
}
