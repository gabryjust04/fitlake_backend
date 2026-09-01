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
import com.fitlake.shared.application.elapsedMilliseconds
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
		val startedAtNanos = System.nanoTime()
		val created = try {
			transactionExecutor.required { createOnce(userId, date, input) }
		} catch (exception: DailyConcurrentCreationException) {
			transactionExecutor.required { createOnce(userId, date, input) }
		}
		logger.atInfo()
			.addKeyValue("event", "daily_capture_created")
			.addKeyValue("outcome", "success")
			.addKeyValue("userRef", userId.value)
			.addKeyValue("captureId", created.captureId.value)
			.addKeyValue("captureType", created.captureType)
			.addKeyValue("captureStatus", created.status)
			.addKeyValue("sourceType", created.createdBy)
			.addKeyValue("entryCount", created.payload.entries.size)
			.addKeyValue("foodItemCount", created.payload.entries.sumOf { it.items.size })
			.addKeyValue("newVersion", created.version)
			.addKeyValue("durationMs", elapsedMilliseconds(startedAtNanos))
			.log("Daily capture created")
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
		if (normalizedRequestId != null && !REQUEST_ID_PATTERN.matches(normalizedRequestId)) {
			throw DailyValidationException("Request ID contains unsupported characters or exceeds 100 characters")
		}
		val startedAtNanos = System.nanoTime()
		val replacement = transactionExecutor.required {
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
				logger.atWarn()
					.addKeyValue("event", "daily_capture_version_conflict")
					.addKeyValue("outcome", "rejected")
					.addKeyValue("errorCode", "DAILY_CAPTURE_VERSION_CONFLICT")
					.addKeyValue("userRef", userId.value)
					.addKeyValue("captureId", captureId.value)
					.addKeyValue("expectedVersion", expectedVersion)
					.addKeyValue("actualVersion", current.version)
					.addKeyValue("durationMs", elapsedMilliseconds(startedAtNanos))
					.log("Daily capture version conflict")
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
					status = persisted.status,
				),
			)
			CaptureReplacement(current, persisted)
		}
		logger.atInfo()
			.addKeyValue("event", "daily_capture_content_replaced")
			.addKeyValue("outcome", "success")
			.addKeyValue("userRef", userId.value)
			.addKeyValue("captureId", captureId.value)
			.addKeyValue("captureType", replacement.after.captureType)
			.addKeyValue("oldStatus", replacement.before.status)
			.addKeyValue("newStatus", replacement.after.status)
			.addKeyValue("oldVersion", replacement.before.version)
			.addKeyValue("newVersion", replacement.after.version)
			.addKeyValue("entryCount", replacement.after.payload.entries.size)
			.addKeyValue("foodItemCount", replacement.after.payload.entries.sumOf { it.items.size })
			.addKeyValue("durationMs", elapsedMilliseconds(startedAtNanos))
			.log("Daily capture content replaced")
		return replacement.after
	}

	private fun createOnce(
		userId: UserId,
		date: LocalDate,
		input: DailyCaptureContentInput,
	): DailyCapture {
		val day = findOrCreateEditableDay(userId, date)
		val payload = contentFactory.create(userId, input)
		val now = clock.instant()
		val persisted = captureRepository.save(DailyCapture.openFromUser(userId, day.dayId, payload, now))
		auditRepository.save(
			DailyCaptureAudit.create(
				captureId = persisted.captureId,
				userId = persisted.userId,
				newPayload = persisted.payload,
				actor = persisted.createdBy,
				requestId = null,
				at = persisted.createdAt,
				newVersion = persisted.version,
				newStatus = persisted.status,
			),
		)
		return persisted
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

	private data class CaptureReplacement(
		val before: DailyCapture,
		val after: DailyCapture,
	)

	companion object {
		private val REQUEST_ID_PATTERN = Regex("^[A-Za-z0-9._-]{1,100}$")
		private val logger = LoggerFactory.getLogger(DailyManualCaptureService::class.java)
	}
}
