package com.fitlake.support

import com.fitlake.daily.application.DailyConcurrentCreationException
import com.fitlake.daily.application.port.DailyCaptureRepository
import com.fitlake.daily.application.port.DailyDayRepository
import com.fitlake.daily.application.port.DailyMetricsRepository
import com.fitlake.daily.domain.capture.DailyCapture
import com.fitlake.daily.domain.capture.DailyCaptureId
import com.fitlake.daily.domain.capture.DailyCaptureStatus
import com.fitlake.daily.domain.common.DailyDay
import com.fitlake.daily.domain.common.DailyDayId
import com.fitlake.daily.domain.metrics.DailyMetrics
import com.fitlake.user.domain.UserId
import java.time.LocalDate
import java.util.concurrent.ConcurrentHashMap

class InMemoryDailyDayRepository : DailyDayRepository {
	private val days = ConcurrentHashMap<DailyDayId, DailyDay>()

	override fun findById(dayId: DailyDayId): DailyDay? = days[dayId]

	override fun findByIdForUpdate(dayId: DailyDayId): DailyDay? = findById(dayId)

	override fun findByUserIdAndDate(userId: UserId, date: LocalDate): DailyDay? =
		days.values.firstOrNull { it.userId == userId && it.dayDate == date }

	override fun findByUserIdAndDateForUpdate(userId: UserId, date: LocalDate): DailyDay? =
		findByUserIdAndDate(userId, date)

	@Synchronized
	override fun save(day: DailyDay): DailyDay {
		val duplicate = days.values.firstOrNull {
			it.userId == day.userId && it.dayDate == day.dayDate && it.dayId != day.dayId
		}
		if (duplicate != null) {
			throw DailyConcurrentCreationException(IllegalStateException("duplicate user day"))
		}
		days[day.dayId] = day
		return day
	}

	fun count(): Int = days.size
}

class InMemoryDailyCaptureRepository : DailyCaptureRepository {
	private val captures = ConcurrentHashMap<DailyCaptureId, DailyCapture>()

	override fun findById(captureId: DailyCaptureId): DailyCapture? = captures[captureId]

	override fun findAllByUserIdAndDayId(userId: UserId, dayId: DailyDayId): List<DailyCapture> =
		captures.values
			.filter { it.userId == userId && it.dayId == dayId }
			.sortedWith(compareBy<DailyCapture> { it.createdAt }.thenBy { it.captureId.value })

	override fun findAllByUserIdAndDayIdAndStatus(
		userId: UserId,
		dayId: DailyDayId,
		status: DailyCaptureStatus,
	): List<DailyCapture> = findAllByUserIdAndDayId(userId, dayId).filter { it.status == status }

	override fun existsByUserIdAndDayIdAndStatus(
		userId: UserId,
		dayId: DailyDayId,
		status: DailyCaptureStatus,
	): Boolean = captures.values.any { it.userId == userId && it.dayId == dayId && it.status == status }

	override fun save(capture: DailyCapture): DailyCapture = capture.also { captures[it.captureId] = it }

	fun count(): Int = captures.size
}

class InMemoryDailyMetricsRepository : DailyMetricsRepository {
	private val metrics = ConcurrentHashMap<DailyDayId, DailyMetrics>()

	var saveCount: Int = 0
		private set

	override fun findByDayId(dayId: DailyDayId): DailyMetrics? = metrics[dayId]

	override fun findByUserIdAndDate(userId: UserId, date: LocalDate): DailyMetrics? =
		metrics.values.firstOrNull { it.userId == userId && it.dayDate == date }

	override fun save(metrics: DailyMetrics): DailyMetrics = metrics.also {
		this.metrics[it.dayId] = it
		saveCount += 1
	}
}
