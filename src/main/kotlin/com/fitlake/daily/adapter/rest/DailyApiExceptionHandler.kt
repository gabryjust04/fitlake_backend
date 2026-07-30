package com.fitlake.daily.adapter.rest

import com.fitlake.daily.application.DailyConcurrentCreationException
import com.fitlake.daily.application.DailyConflictException
import com.fitlake.daily.application.DailyNotFoundException
import com.fitlake.daily.application.DailyStateCorruptionException
import com.fitlake.daily.application.DailyValidationException
import org.springframework.dao.OptimisticLockingFailureException
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

data class DailyApiError(
	val error: String,
	val message: String,
	val fieldErrors: Map<String, String> = emptyMap(),
)

@RestControllerAdvice
class DailyApiExceptionHandler {
	@ExceptionHandler(DailyNotFoundException::class)
	fun notFound(exception: DailyNotFoundException): ResponseEntity<DailyApiError> =
		response(HttpStatus.NOT_FOUND, "not_found", exception.message ?: "Daily resource was not found")

	@ExceptionHandler(
		DailyConflictException::class,
		DailyConcurrentCreationException::class,
		OptimisticLockingFailureException::class,
	)
	fun conflict(exception: RuntimeException): ResponseEntity<DailyApiError> =
		response(HttpStatus.CONFLICT, "conflict", exception.message ?: "Daily state conflict")

	@ExceptionHandler(DailyValidationException::class)
	fun validation(exception: DailyValidationException): ResponseEntity<DailyApiError> =
		response(HttpStatus.BAD_REQUEST, "validation_error", exception.message ?: "Invalid daily payload")

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

	@ExceptionHandler(DailyStateCorruptionException::class)
	fun corruptedState(): ResponseEntity<DailyApiError> =
		response(HttpStatus.INTERNAL_SERVER_ERROR, "internal_server_error", "Daily state is inconsistent")

	private fun response(status: HttpStatus, error: String, message: String): ResponseEntity<DailyApiError> =
		ResponseEntity.status(status).body(DailyApiError(error, message))
}
