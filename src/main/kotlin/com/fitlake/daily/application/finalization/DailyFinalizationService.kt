package com.fitlake.daily.application.finalization

import com.fitlake.daily.application.DailyConflictException
import com.fitlake.daily.application.DailyNotFoundException
import com.fitlake.daily.application.DailyStateCorruptionException
import com.fitlake.daily.application.port.DailyCaptureRepository
import com.fitlake.daily.application.port.DailyDayRepository
import com.fitlake.daily.application.port.DailyMetricsRepository
import com.fitlake.daily.domain.capture.DailyCaptureStatus
import com.fitlake.daily.domain.common.DailyDayStatus
import com.fitlake.daily.domain.metrics.DailyMetrics
import com.fitlake.shared.application.TransactionExecutor
import com.fitlake.user.domain.UserId
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.LocalDate

@Service
class DailyFinalizationService(
	private val dayRepository: DailyDayRepository,
	private val captureRepository: DailyCaptureRepository,
	private val metricsRepository: DailyMetricsRepository,
	private val projectionService: DailyMetricsProjectionService,
	private val transactionExecutor: TransactionExecutor,
	private val clock: Clock,
) {
	fun finalizeDay(userId: UserId, date: LocalDate): DailyMetrics = transactionExecutor.required {
		val day = dayRepository.findByUserIdAndDateForUpdate(userId, date)
			?: throw DailyNotFoundException.day(date)

		if (day.status == DailyDayStatus.CONFIRMED) {
			return@required metricsRepository.findByDayId(day.dayId)
				?: throw DailyStateCorruptionException("Confirmed day has no metrics snapshot")
		}

		if (
			captureRepository.existsByUserIdAndDayIdAndStatus(
				userId,
				day.dayId,
				DailyCaptureStatus.OPEN,
			)
		) {
			throw DailyConflictException("Day cannot be finalized while open captures exist")
		}

		val accepted = captureRepository.findAllByUserIdAndDayIdAndStatus(
			userId,
			day.dayId,
			DailyCaptureStatus.ACCEPTED,
		)
		val now = clock.instant()
		val metrics = projectionService.project(
			day = day,
			captures = accepted,
			existing = metricsRepository.findByDayId(day.dayId),
			at = now,
		)

		val savedMetrics = metricsRepository.save(metrics)
		dayRepository.save(day.confirm(now))
		savedMetrics
	}
}
