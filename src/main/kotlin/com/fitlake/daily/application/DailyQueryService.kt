package com.fitlake.daily.application

import com.fitlake.daily.application.port.DailyCaptureRepository
import com.fitlake.daily.application.port.DailyDayRepository
import com.fitlake.daily.application.port.DailyMetricsRepository
import com.fitlake.daily.domain.capture.DailyCapture
import com.fitlake.daily.domain.common.DailyDay
import com.fitlake.daily.domain.metrics.DailyMetrics
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
) {
	fun getDay(userId: UserId, date: LocalDate): DailyDayView {
		val day = dayRepository.findByUserIdAndDate(userId, date) ?: throw DailyNotFoundException.day(date)
		return DailyDayView(
			day = day,
			captures = captureRepository.findAllByUserIdAndDayId(userId, day.dayId),
			metrics = metricsRepository.findByDayId(day.dayId),
		)
	}

	fun getMetrics(userId: UserId, date: LocalDate): DailyMetrics =
		metricsRepository.findByUserIdAndDate(userId, date) ?: throw DailyNotFoundException.metrics(date)
}
