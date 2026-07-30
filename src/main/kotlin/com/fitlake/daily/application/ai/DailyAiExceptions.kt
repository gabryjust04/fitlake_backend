package com.fitlake.daily.application.ai

open class DailyAiException(
	val errorCode: String,
	val safeMessage: String,
	cause: Throwable? = null,
) : RuntimeException(safeMessage, cause)

class DailyAiConfigurationException(cause: Throwable? = null) : DailyAiException(
	errorCode = "AI_NOT_CONFIGURED",
	safeMessage = "Daily AI is not configured",
	cause = cause,
)

class DailyAiTimeoutException(cause: Throwable? = null) : DailyAiException(
	errorCode = "AI_TIMEOUT",
	safeMessage = "The AI provider timed out",
	cause = cause,
)

class DailyAiProviderUnavailableException(cause: Throwable? = null) : DailyAiException(
	errorCode = "AI_PROVIDER_UNAVAILABLE",
	safeMessage = "The AI provider is unavailable",
	cause = cause,
)

class DailyAiInvalidOutputException(cause: Throwable? = null) : DailyAiException(
	errorCode = "AI_INVALID_OUTPUT",
	safeMessage = "The AI provider returned an invalid tool result",
	cause = cause,
)

class DailyAiOperationInProgressException : DailyAiException(
	errorCode = "AI_OPERATION_IN_PROGRESS",
	safeMessage = "An operation with this idempotency key is already being processed",
)

class DailyAiIdempotencyConflictException : DailyAiException(
	errorCode = "IDEMPOTENCY_KEY_CONFLICT",
	safeMessage = "The idempotency key was already used for a different request",
)

class DailyAiConcurrentRequestException(cause: Throwable? = null) : RuntimeException(
	"A Daily AI request was created concurrently",
	cause,
)

class DailyAiPersistenceException(cause: Throwable? = null) : DailyAiException(
	errorCode = "AI_PERSISTENCE_ERROR",
	safeMessage = "The interpreted Daily data could not be persisted",
	cause = cause,
)

class DailyAiRecordedFailureException(
	errorCode: String,
	safeMessage: String,
) : DailyAiException(errorCode, safeMessage)
