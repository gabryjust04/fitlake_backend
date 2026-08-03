package com.fitlake.food.adapter.rest

import com.fitlake.food.application.UserFoodConflictException
import com.fitlake.food.application.UserFoodNotFoundException
import com.fitlake.food.application.UserFoodPersistenceException
import com.fitlake.food.application.UserFoodValidationException
import com.fitlake.shared.logging.sanitizedForTechnicalLogging
import jakarta.validation.ConstraintViolationException
import org.slf4j.LoggerFactory
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.dao.OptimisticLockingFailureException
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.MissingServletRequestParameterException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.method.annotation.HandlerMethodValidationException
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException

data class UserFoodApiError(
	val error: String,
	val message: String,
	val fieldErrors: Map<String, String> = emptyMap(),
)

@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes = [UserFoodController::class])
class UserFoodApiExceptionHandler {
	@ExceptionHandler(UserFoodNotFoundException::class)
	fun notFound(exception: UserFoodNotFoundException): ResponseEntity<UserFoodApiError> =
		response(HttpStatus.NOT_FOUND, "not_found", exception.message ?: "User food was not found")

	@ExceptionHandler(
		UserFoodConflictException::class,
		OptimisticLockingFailureException::class,
		DataIntegrityViolationException::class,
	)
	fun conflict(exception: RuntimeException): ResponseEntity<UserFoodApiError> =
		response(
			HttpStatus.CONFLICT,
			"conflict",
			if (exception is UserFoodConflictException) exception.message ?: "User food conflict" else "User food conflict",
		)

	@ExceptionHandler(UserFoodValidationException::class)
	fun validation(exception: UserFoodValidationException): ResponseEntity<UserFoodApiError> =
		response(HttpStatus.BAD_REQUEST, "validation_error", exception.message ?: "Invalid user food")

	@ExceptionHandler(MethodArgumentNotValidException::class)
	fun beanValidation(exception: MethodArgumentNotValidException): ResponseEntity<UserFoodApiError> {
		val fields = exception.bindingResult.fieldErrors.associate { error ->
			error.field to (error.defaultMessage ?: "invalid value")
		}
		return ResponseEntity.status(HttpStatus.BAD_REQUEST)
			.body(UserFoodApiError("validation_error", "Request validation failed", fields))
	}

	@ExceptionHandler(HttpMessageNotReadableException::class)
	fun unreadable(): ResponseEntity<UserFoodApiError> =
		response(HttpStatus.BAD_REQUEST, "invalid_request", "Request body is missing or malformed")

	@ExceptionHandler(
		MissingServletRequestParameterException::class,
		ConstraintViolationException::class,
		HandlerMethodValidationException::class,
		MethodArgumentTypeMismatchException::class,
	)
	fun invalidParameters(): ResponseEntity<UserFoodApiError> =
		response(HttpStatus.BAD_REQUEST, "invalid_request", "Request parameters are missing or invalid")

	@ExceptionHandler(UserFoodPersistenceException::class)
	fun persistenceFailure(exception: UserFoodPersistenceException): ResponseEntity<UserFoodApiError> {
		logger.atError()
			.addKeyValue("event", "user_food_persistence_failed")
			.addKeyValue("outcome", "failure")
			.addKeyValue("errorCode", "USER_FOOD_PERSISTENCE_ERROR")
			.addKeyValue("exceptionType", exception.javaClass.name)
			.setCause(exception.sanitizedForTechnicalLogging())
			.log("User food persistence failed")
		return response(HttpStatus.INTERNAL_SERVER_ERROR, "internal_server_error", "User food could not be persisted")
	}

	private fun response(status: HttpStatus, error: String, message: String): ResponseEntity<UserFoodApiError> =
		ResponseEntity.status(status).body(UserFoodApiError(error, message))

	private companion object {
		val logger = LoggerFactory.getLogger(UserFoodApiExceptionHandler::class.java)
	}
}
