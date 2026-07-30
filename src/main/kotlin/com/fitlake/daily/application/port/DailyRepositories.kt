package com.fitlake.daily.application.port

import com.fitlake.daily.domain.capture.DailyCapture
import com.fitlake.daily.domain.capture.DailyCaptureId
import com.fitlake.daily.domain.capture.DailyCaptureStatus
import com.fitlake.daily.domain.common.DailyDay
import com.fitlake.daily.domain.common.DailyDayId
import com.fitlake.daily.domain.metrics.DailyMetrics
import com.fitlake.user.domain.UserId
import java.time.LocalDate

interface DailyDayRepository {
	fun findById(dayId: DailyDayId): DailyDay?
	fun findByIdForUpdate(dayId: DailyDayId): DailyDay?
	fun findByUserIdAndDate(userId: UserId, date: LocalDate): DailyDay?
	fun findByUserIdAndDateForUpdate(userId: UserId, date: LocalDate): DailyDay?
	fun save(day: DailyDay): DailyDay
}

interface DailyCaptureRepository {
	fun findById(captureId: DailyCaptureId): DailyCapture?
	fun findAllByUserIdAndDayId(userId: UserId, dayId: DailyDayId): List<DailyCapture>
	fun findAllByUserIdAndDayIdAndStatus(
		userId: UserId,
		dayId: DailyDayId,
		status: DailyCaptureStatus,
	): List<DailyCapture>
	fun existsByUserIdAndDayIdAndStatus(
		userId: UserId,
		dayId: DailyDayId,
		status: DailyCaptureStatus,
	): Boolean
	fun save(capture: DailyCapture): DailyCapture
}

interface DailyMetricsRepository {
	fun findByDayId(dayId: DailyDayId): DailyMetrics?
	fun findByUserIdAndDate(userId: UserId, date: LocalDate): DailyMetrics?
	fun save(metrics: DailyMetrics): DailyMetrics
}
