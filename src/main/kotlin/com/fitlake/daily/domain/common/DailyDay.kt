package com.fitlake.daily.domain.common

import com.fitlake.user.domain.UserId
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

@JvmInline
value class DailyDayId(val value: UUID)

enum class DailyDayStatus {
	OPEN,
	CONFIRMED,
	REOPENED,
}

data class DailyDay(
	val dayId: DailyDayId,
	val userId: UserId,
	val dayDate: LocalDate,
	val status: DailyDayStatus,
	val openedAt: Instant,
	val confirmedAt: Instant?,
	val reopenedAt: Instant?,
	val createdAt: Instant,
	val updatedAt: Instant,
	val version: Long,
) {
	init {
		require(version >= 0) { "Day version must not be negative" }
		require(!updatedAt.isBefore(createdAt)) { "Day update cannot precede creation" }
		require(status != DailyDayStatus.CONFIRMED || confirmedAt != null) {
			"A confirmed day requires a confirmation timestamp"
		}
	}

	fun ensureEditable() {
		check(status != DailyDayStatus.CONFIRMED) { "Confirmed day cannot be modified" }
	}

	fun confirm(at: Instant): DailyDay {
		if (status == DailyDayStatus.CONFIRMED) {
			return this
		}
		return copy(
			status = DailyDayStatus.CONFIRMED,
			confirmedAt = at,
			updatedAt = maxOf(updatedAt, at),
		)
	}

	companion object {
		fun open(userId: UserId, dayDate: LocalDate, at: Instant): DailyDay = DailyDay(
			dayId = DailyDayId(UUID.randomUUID()),
			userId = userId,
			dayDate = dayDate,
			status = DailyDayStatus.OPEN,
			openedAt = at,
			confirmedAt = null,
			reopenedAt = null,
			createdAt = at,
			updatedAt = at,
			version = 0,
		)
	}
}
