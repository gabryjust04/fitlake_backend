package com.fitlake.daily.application.capture

import com.fitlake.daily.application.DailyConcurrentCreationException
import com.fitlake.daily.application.DailyConflictException
import com.fitlake.daily.application.DailyNotFoundException
import com.fitlake.daily.application.DailyValidationException
import com.fitlake.daily.application.port.DailyCaptureAuditRepository
import com.fitlake.daily.application.port.DailyCaptureRepository
import com.fitlake.daily.application.port.DailyDayRepository
import com.fitlake.daily.application.port.DailyMetricsRepository
import com.fitlake.daily.domain.audit.DailyCaptureAudit
import com.fitlake.daily.domain.capture.DailyCapture
import com.fitlake.daily.domain.capture.DailyCaptureId
import com.fitlake.daily.domain.capture.DailyCaptureStatus
import com.fitlake.daily.domain.common.DailyDay
import com.fitlake.daily.domain.common.DailyDayStatus
import com.fitlake.shared.application.TransactionExecutor
import com.fitlake.user.domain.UserId
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.LocalDate

@Service
class DailyManualCaptureService(
	private val dayRepository: DailyDayRepository,
	private val captureRepository: DailyCaptureRepository,
	private val metricsRepository: DailyMetricsRepository,
	private val auditRepository: DailyCaptureAuditRepository,
	private val contentFactory: DailyCaptureContentFactory,
	private val transactionExecutor: TransactionExecutor,
	private val clock: Clock,
) {
	fun create(userId: UserId, date: LocalDate, input: DailyCaptureContentInput): DailyCapture {
		val startedAt = System.nanoTime()
		val created = try {
			transactionExecutor.required { createOnce(userId, date, input) }
		} catch (exception: DailyConcurrentCreationException) {
			transactionExecutor.required { createOnce(userId, date, input) }
		}
		logger.info(
			"event=daily_capture_manual_created userId={} captureId={} day={} captureType={} " +
				"entryCount={} foodItemCount={} version={} durationMs={}",
			userId.value,
			created.captureId.value,
			date,
			created.captureType,
			created.payload.entries.size,
			created.payload.entries.sumOf { it.items.size },
			created.version,
			elapsedMillis(startedAt),
		)
		return created
	}

	fun replace(
		userId: UserId,
		captureId: DailyCaptureId,
		expectedVersion: Long,
		input: DailyCaptureContentInput,
		requestId: String?,
	): DailyCapture {
		if (expectedVersion < 0) throw DailyValidationException("Capture version must not be negative")
		val normalizedRequestId = requestId?.trim()?.takeIf(String::isNotEmpty)
		if (normalizedRequestId != null && normalizedRequestId.length > 100) {
			throw DailyValidationException("Request ID must not exceed 100 characters")
		}
		val startedAt = System.nanoTime()
		val updated = transactionExecutor.required {
			val candidate = captureRepository.findByIdAndUserId(captureId, userId)
				?: throw DailyNotFoundException.capture(captureId.value)
			val day = dayRepository.findByIdForUpdate(candidate.dayId)
				?: throw DailyNotFoundException.capture(captureId.value)
			if (day.userId != userId || day.status == DailyDayStatus.CONFIRMED) {
				throw DailyConflictException("Confirmed day captures cannot be edited")
			}
			val current = captureRepository.findByIdAndUserIdForUpdate(captureId, userId)
				?: throw DailyNotFoundException.capture(captureId.value)
			if (current.dayId != day.dayId) throw DailyNotFoundException.capture(captureId.value)
			if (current.version != expectedVersion) {
				logger.warn(
					"event=daily_capture_version_conflict userId={} captureId={} expectedVersion={} actualVersion={}",
					userId.value,
					captureId.value,
					expectedVersion,
					current.version,
				)
				throw DailyConflictException("Capture version is stale")
			}
			ensureEditable(current)
			if (
				current.status == DailyCaptureStatus.ACCEPTED &&
				day.status != DailyDayStatus.REOPENED &&
				metricsRepository.findByDayId(day.dayId) != null
			) {
				throw DailyConflictException("Accepted capture cannot be edited while finalized metrics exist")
			}
			val payload = contentFactory.replace(userId, current.payload, input)
			val now = clock.instant()
			val persisted = captureRepository.save(current.replacePayload(payload, now))
			if (persisted.version <= current.version) {
				throw IllegalStateException("Capture persistence did not increment its optimistic-lock version")
			}
			auditRepository.save(
				DailyCaptureAudit.uiEdit(
					captureId = captureId,
					userId = userId,
					oldPayload = current.payload,
					newPayload = persisted.payload,
					oldVersion = current.version,
					newVersion = persisted.version,
					requestId = normalizedRequestId,
					at = now,
				),
			)
			persisted
		}
		logger.info(
			"event=daily_capture_content_replaced userId={} captureId={} captureType={} entryCount={} " +
				"foodItemCount={} version={} durationMs={}",
			userId.value,
			captureId.value,
			updated.captureType,
			updated.payload.entries.size,
			updated.payload.entries.sumOf { it.items.size },
			updated.version,
			elapsedMillis(startedAt),
		)
		return updated
	}

	private fun createOnce(
		userId: UserId,
		date: LocalDate,
		input: DailyCaptureContentInput,
	): DailyCapture {
		val day = findOrCreateEditableDay(userId, date)
		val payload = contentFactory.create(userId, input)
		return captureRepository.save(DailyCapture.openFromUser(userId, day.dayId, payload, clock.instant()))
	}

	private fun findOrCreateEditableDay(userId: UserId, date: LocalDate): DailyDay {
		val day = dayRepository.findByUserIdAndDateForUpdate(userId, date)
			?: dayRepository.save(DailyDay.open(userId, date, clock.instant()))
		if (day.status == DailyDayStatus.CONFIRMED) {
			throw DailyConflictException("Confirmed day cannot receive new captures")
		}
		return day
	}

	private fun ensureEditable(capture: DailyCapture) {
		if (capture.status != DailyCaptureStatus.OPEN && capture.status != DailyCaptureStatus.ACCEPTED) {
			throw DailyConflictException("Only open or accepted captures can be edited")
		}
	}

	private fun elapsedMillis(startedAt: Long): Long = (System.nanoTime() - startedAt) / 1_000_000

	companion object {
		private val logger = LoggerFactory.getLogger(DailyManualCaptureService::class.java)
	}
}
