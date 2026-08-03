package com.fitlake.daily.application.finalization

import com.fitlake.daily.application.DailyConflictException
import com.fitlake.daily.application.DailyNotFoundException
import com.fitlake.daily.application.DailyStateCorruptionException
import com.fitlake.daily.application.port.DailyCaptureRepository
import com.fitlake.daily.application.port.DailyDayRepository
import com.fitlake.daily.application.port.DailyMetricsRepository
import com.fitlake.daily.domain.capture.DailyCaptureStatus
import com.fitlake.daily.domain.common.DailyDay
import com.fitlake.daily.domain.common.DailyDayStatus
import com.fitlake.daily.domain.metrics.DailyMetrics
import com.fitlake.shared.application.TransactionExecutor
import com.fitlake.shared.application.elapsedMilliseconds
import com.fitlake.user.domain.UserId
import org.slf4j.LoggerFactory
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
	fun finalizeDay(userId: UserId, date: LocalDate): DailyMetrics {
		val startedAtNanos = System.nanoTime()
		val finalization = transactionExecutor.required {
			val day = dayRepository.findByUserIdAndDateForUpdate(userId, date)
				?: throw DailyNotFoundException.day(date)
			val existingMetrics = metricsRepository.findByDayId(day.dayId)

			if (day.status == DailyDayStatus.CONFIRMED) {
				return@required FinalizationResult(
					metrics = requireConsistentMetrics(day, existingMetrics, DailyDayStatus.CONFIRMED),
					day = day,
					recalculated = false,
					acceptedCaptureCount = 0,
					foodItemCount = 0,
				)
			}
			if (day.status == DailyDayStatus.REOPENED) {
				requireConsistentMetrics(day, existingMetrics, DailyDayStatus.REOPENED)
			} else if (existingMetrics != null) {
				throw DailyStateCorruptionException("Open day unexpectedly has a metrics snapshot")
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
			val projected = projectionService.project(
				day = day,
				captures = accepted,
				existing = existingMetrics,
				at = now,
			)

			val savedMetrics = metricsRepository.save(projected)
			dayRepository.save(day.confirm(now))
			FinalizationResult(
				metrics = savedMetrics,
				day = day,
				recalculated = true,
				acceptedCaptureCount = accepted.size,
				foodItemCount = accepted.sumOf { capture -> capture.payload.entries.sumOf { it.items.size } },
			)
		}
		if (finalization.recalculated) {
			logger.atInfo()
				.addKeyValue("event", "daily_metrics_recalculated")
				.addKeyValue("outcome", "success")
				.addKeyValue("userRef", userId.value)
				.addKeyValue("dayId", finalization.day.dayId.value)
				.addKeyValue("oldStatus", finalization.day.status)
				.addKeyValue("newStatus", DailyDayStatus.CONFIRMED)
				.addKeyValue("acceptedCaptureCount", finalization.acceptedCaptureCount)
				.addKeyValue("foodItemCount", finalization.foodItemCount)
				.addKeyValue("durationMs", elapsedMilliseconds(startedAtNanos))
				.log("Daily metrics recalculated")
		} else {
			logger.atDebug()
				.addKeyValue("event", "daily_metrics_recalculation_skipped")
				.addKeyValue("outcome", "no_op")
				.addKeyValue("userRef", userId.value)
				.addKeyValue("dayId", finalization.day.dayId.value)
				.addKeyValue("durationMs", elapsedMilliseconds(startedAtNanos))
				.log("Daily metrics recalculation skipped")
		}
		return finalization.metrics
	}

	private fun requireConsistentMetrics(
		day: DailyDay,
		metrics: DailyMetrics?,
		expectedStatus: DailyDayStatus,
	): DailyMetrics {
		val snapshot = metrics
			?: throw DailyStateCorruptionException("${day.status} day has no metrics snapshot")
		if (
			snapshot.dayId != day.dayId ||
			snapshot.userId != day.userId ||
			snapshot.dayDate != day.dayDate ||
			snapshot.status != expectedStatus ||
			snapshot.confirmedAt == null
		) {
			throw DailyStateCorruptionException("${day.status} day has an inconsistent metrics snapshot")
		}
		return snapshot
	}

	private data class FinalizationResult(
		val metrics: DailyMetrics,
		val day: DailyDay,
		val recalculated: Boolean,
		val acceptedCaptureCount: Int,
		val foodItemCount: Int,
	)

	private companion object {
		val logger = LoggerFactory.getLogger(DailyFinalizationService::class.java)
	}
}
