package com.fitlake.daily.application.finalization

import com.fitlake.daily.application.DailyConflictException
import com.fitlake.daily.application.DailyNotFoundException
import com.fitlake.daily.application.DailyStateCorruptionException
import com.fitlake.daily.application.port.DailyDayRepository
import com.fitlake.daily.application.port.DailyMetricsRepository
import com.fitlake.daily.domain.common.DailyDay
import com.fitlake.daily.domain.common.DailyDayStatus
import com.fitlake.shared.application.TransactionExecutor
import com.fitlake.user.domain.UserId
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.LocalDate

@Service
class DailyDayReopeningService(
	private val dayRepository: DailyDayRepository,
	private val metricsRepository: DailyMetricsRepository,
	private val transactionExecutor: TransactionExecutor,
	private val clock: Clock,
) {
	fun reopenDay(userId: UserId, date: LocalDate): DailyDay = transactionExecutor.required {
		val day = dayRepository.findByUserIdAndDateForUpdate(userId, date)
			?: throw DailyNotFoundException.day(date)

		when (day.status) {
			DailyDayStatus.REOPENED -> {
				requireConsistentMetrics(day, DailyDayStatus.REOPENED)
				return@required day
			}
			DailyDayStatus.OPEN -> throw DailyConflictException("Only a confirmed day can be reopened")
			DailyDayStatus.CONFIRMED -> Unit
		}

		val currentMetrics = requireConsistentMetrics(day, DailyDayStatus.CONFIRMED)

		val now = clock.instant()
		metricsRepository.save(currentMetrics.markReopened(now))
		dayRepository.save(day.reopen(now))
	}

	private fun requireConsistentMetrics(
		day: DailyDay,
		expectedStatus: DailyDayStatus,
	) = metricsRepository.findByDayId(day.dayId)?.takeIf { metrics ->
		metrics.userId == day.userId &&
			metrics.dayDate == day.dayDate &&
			metrics.status == expectedStatus &&
			metrics.confirmedAt != null
	} ?: throw DailyStateCorruptionException("${day.status} day has no consistent metrics snapshot")
}
