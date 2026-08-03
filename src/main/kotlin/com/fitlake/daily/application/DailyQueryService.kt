package com.fitlake.daily.application

import com.fitlake.daily.application.port.DailyCaptureRepository
import com.fitlake.daily.application.port.DailyDayRepository
import com.fitlake.daily.application.port.DailyMetricsRepository
import com.fitlake.daily.domain.capture.DailyCapture
import com.fitlake.daily.domain.capture.DailyCaptureId
import com.fitlake.daily.domain.common.DailyDay
import com.fitlake.daily.domain.metrics.DailyMetrics
import com.fitlake.shared.application.TransactionExecutor
import com.fitlake.user.domain.UserId
import org.springframework.stereotype.Service
import java.time.LocalDate

data class DailyDayView(
	val day: DailyDay,
	val captures: List<DailyCapture>,
	val metrics: DailyMetrics?,
)

@Service
class DailyQueryService(
	private val dayRepository: DailyDayRepository,
	private val captureRepository: DailyCaptureRepository,
	private val metricsRepository: DailyMetricsRepository,
	private val transactionExecutor: TransactionExecutor,
) {
	fun getCapture(userId: UserId, captureId: DailyCaptureId): DailyCapture =
		captureRepository.findByIdAndUserId(captureId, userId)
			?: throw DailyNotFoundException.capture(captureId.value)

	fun getCaptures(userId: UserId, date: LocalDate): List<DailyCapture> {
		val day = dayRepository.findByUserIdAndDate(userId, date) ?: throw DailyNotFoundException.day(date)
		return captureRepository.findAllByUserIdAndDayId(userId, day.dayId)
	}

	fun getDay(userId: UserId, date: LocalDate): DailyDayView = transactionExecutor.required {
		val day = dayRepository.findByUserIdAndDateForUpdate(userId, date)
			?: throw DailyNotFoundException.day(date)
		DailyDayView(
			day = day,
			captures = captureRepository.findAllByUserIdAndDayId(userId, day.dayId),
			metrics = metricsRepository.findByDayId(day.dayId),
		)
	}

	fun getMetrics(userId: UserId, date: LocalDate): DailyMetrics =
		metricsRepository.findByUserIdAndDate(userId, date) ?: throw DailyNotFoundException.metrics(date)
}
