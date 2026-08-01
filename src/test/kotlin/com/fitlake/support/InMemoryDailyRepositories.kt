package com.fitlake.support

import com.fitlake.daily.application.DailyConcurrentCreationException
import com.fitlake.daily.application.ai.DailyAiConcurrentRequestException
import com.fitlake.daily.application.port.AiInterpretationLogRepository
import com.fitlake.daily.application.port.DailyCaptureRepository
import com.fitlake.daily.application.port.DailyDayRepository
import com.fitlake.daily.application.port.DailyInboxEventRepository
import com.fitlake.daily.application.port.DailyMetricsRepository
import com.fitlake.daily.domain.ai.AiInterpretationLog
import com.fitlake.daily.domain.capture.DailyCapture
import com.fitlake.daily.domain.capture.DailyCaptureId
import com.fitlake.daily.domain.capture.DailyCaptureStatus
import com.fitlake.daily.domain.common.DailyDay
import com.fitlake.daily.domain.common.DailyDayId
import com.fitlake.daily.domain.inbox.DailyInboxChannel
import com.fitlake.daily.domain.inbox.DailyInboxEvent
import com.fitlake.daily.domain.inbox.DailyInboxEventId
import com.fitlake.daily.domain.metrics.DailyMetrics
import com.fitlake.user.domain.UserId
import org.springframework.dao.OptimisticLockingFailureException
import java.time.LocalDate
import java.util.UUID
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

	fun all(): List<DailyDay> = days.values.toList()

	fun clear() = days.clear()
}

class InMemoryDailyCaptureRepository : DailyCaptureRepository {
	private val captures = ConcurrentHashMap<DailyCaptureId, DailyCapture>()

	@Volatile
	var failureOnNextSave: RuntimeException? = null

	override fun findById(captureId: DailyCaptureId): DailyCapture? = captures[captureId]

	override fun findByIdForUpdate(captureId: DailyCaptureId): DailyCapture? = findById(captureId)

	override fun findByIdAndUserId(captureId: DailyCaptureId, userId: UserId): DailyCapture? =
		findById(captureId)?.takeIf { it.userId == userId }

	override fun findByIdAndUserIdForUpdate(captureId: DailyCaptureId, userId: UserId): DailyCapture? =
		findByIdAndUserId(captureId, userId)

	override fun findBySourceEventId(sourceEventId: UUID): DailyCapture? =
		captures.values.firstOrNull { it.sourceEventId == sourceEventId }

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

	@Synchronized
	override fun save(capture: DailyCapture): DailyCapture {
		failureOnNextSave?.let { failure ->
			failureOnNextSave = null
			throw failure
		}
		val existing = captures[capture.captureId]
		val persisted = if (existing == null) {
			capture
		} else {
			if (capture.version != existing.version) {
				throw OptimisticLockingFailureException("stale daily capture")
			}
			capture.copy(version = capture.version + 1)
		}
		captures[persisted.captureId] = persisted
		return persisted
	}

	fun count(): Int = captures.size

	fun all(): List<DailyCapture> = captures.values
		.sortedWith(compareBy<DailyCapture> { it.createdAt }.thenBy { it.captureId.value })

	fun clear() {
		captures.clear()
		failureOnNextSave = null
	}
}

class InMemoryDailyInboxEventRepository : DailyInboxEventRepository {
	private val events = ConcurrentHashMap<DailyInboxEventId, DailyInboxEvent>()

	override fun findById(inboxEventId: DailyInboxEventId): DailyInboxEvent? = events[inboxEventId]

	override fun findByIdForUpdate(inboxEventId: DailyInboxEventId): DailyInboxEvent? = findById(inboxEventId)

	override fun findByUserIdAndChannelAndSourceMessageId(
		userId: UserId,
		channel: DailyInboxChannel,
		sourceMessageId: String,
	): DailyInboxEvent? = events.values.firstOrNull {
		it.userId == userId && it.channel == channel && it.sourceMessageId == sourceMessageId
	}

	@Synchronized
	override fun save(event: DailyInboxEvent): DailyInboxEvent {
		val duplicate = events.values.firstOrNull {
			it.userId == event.userId &&
				it.channel == event.channel &&
				it.sourceMessageId == event.sourceMessageId &&
				it.inboxEventId != event.inboxEventId
		}
		if (duplicate != null) {
			throw DailyAiConcurrentRequestException(IllegalStateException("duplicate inbox idempotency key"))
		}
		events[event.inboxEventId] = event
		return event
	}

	fun count(): Int = events.size

	fun all(): List<DailyInboxEvent> = events.values.sortedBy { it.createdAt }

	fun clear() = events.clear()
}

class InMemoryAiInterpretationLogRepository : AiInterpretationLogRepository {
	private val logs = ConcurrentHashMap<DailyInboxEventId, AiInterpretationLog>()

	override fun findByInboxEventId(inboxEventId: DailyInboxEventId): AiInterpretationLog? = logs[inboxEventId]

	@Synchronized
	override fun save(log: AiInterpretationLog): AiInterpretationLog {
		val existing = logs[log.inboxEventId]
		if (existing != null && existing.aiLogId != log.aiLogId) {
			throw DailyAiConcurrentRequestException(IllegalStateException("duplicate interpretation log"))
		}
		logs[log.inboxEventId] = log
		return log
	}

	fun count(): Int = logs.size

	fun all(): List<AiInterpretationLog> = logs.values.sortedBy { it.createdAt }

	fun clear() = logs.clear()
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
