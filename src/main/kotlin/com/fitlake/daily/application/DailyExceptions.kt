package com.fitlake.daily.application

import java.time.LocalDate
import java.util.UUID

class DailyNotFoundException(message: String) : RuntimeException(message) {
	companion object {
		fun day(date: LocalDate) = DailyNotFoundException("Daily day was not found for $date")
		fun capture(captureId: UUID) = DailyNotFoundException("Daily capture was not found: $captureId")
		fun metrics(date: LocalDate) = DailyNotFoundException("Daily metrics were not found for $date")
	}
}

class DailyConflictException(message: String) : RuntimeException(message)

class DailyValidationException(message: String) : RuntimeException(message)

class DailyConcurrentCreationException(cause: Throwable) : RuntimeException(
	"Daily day was created concurrently",
	cause,
)

class DailyStateCorruptionException(message: String) : RuntimeException(message)
