package com.fitlake.shared.logging

/**
 * Keeps the useful call stack while deliberately dropping exception messages,
 * causes and suppressed exceptions that may contain user-authored or provider data.
 */
internal fun Throwable.sanitizedForTechnicalLogging(): Throwable =
	SanitizedTechnicalException(javaClass.name, stackTrace)

private class SanitizedTechnicalException(
	exceptionType: String,
	originalStackTrace: Array<StackTraceElement>,
) : RuntimeException(exceptionType) {
	init {
		stackTrace = originalStackTrace
	}
}
