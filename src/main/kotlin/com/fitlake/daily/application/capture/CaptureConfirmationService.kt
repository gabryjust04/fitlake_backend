package com.fitlake.daily.application.capture

import com.fitlake.daily.application.DailyConflictException
import com.fitlake.daily.application.DailyNotFoundException
import com.fitlake.daily.application.port.DailyCaptureAuditRepository
import com.fitlake.daily.application.port.DailyCaptureRepository
import com.fitlake.daily.application.port.DailyDayRepository
import com.fitlake.daily.domain.audit.DailyCaptureAudit
import com.fitlake.daily.domain.capture.DailyCapture
import com.fitlake.daily.domain.capture.DailyCaptureId
import com.fitlake.daily.domain.common.DailyDayStatus
import com.fitlake.shared.application.TransactionExecutor
import com.fitlake.shared.application.elapsedMilliseconds
import com.fitlake.user.domain.UserId
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.Instant

@Service
class CaptureConfirmationService(
	private val dayRepository: DailyDayRepository,
	private val captureRepository: DailyCaptureRepository,
	private val auditRepository: DailyCaptureAuditRepository,
	private val captureService: DailyCaptureService,
	private val transactionExecutor: TransactionExecutor,
	private val clock: Clock,
) {
	fun accept(userId: UserId, captureId: DailyCaptureId): DailyCapture =
		transition(
			userId = userId,
			captureId = captureId,
			event = "daily_capture_accepted",
			message = "Daily capture accepted",
			change = { capture, at -> capture.accept(at) },
			audit = { before, after, at ->
				DailyCaptureAudit.accept(
					captureId = after.captureId,
					userId = after.userId,
					oldVersion = before.version,
					newVersion = after.version,
					requestId = null,
					at = at,
				)
			},
		)

	fun reject(userId: UserId, captureId: DailyCaptureId): DailyCapture =
		transition(
			userId = userId,
			captureId = captureId,
			event = "daily_capture_rejected",
			message = "Daily capture rejected",
			change = { capture, at -> capture.reject(at) },
			audit = { before, after, at ->
				DailyCaptureAudit.reject(
					captureId = after.captureId,
					userId = after.userId,
					oldVersion = before.version,
					newVersion = after.version,
					reasonCode = null,
					requestId = null,
					at = at,
				)
			},
		)

	private fun transition(
		userId: UserId,
		captureId: DailyCaptureId,
		event: String,
		message: String,
		change: (DailyCapture, Instant) -> DailyCapture,
		audit: (DailyCapture, DailyCapture, Instant) -> DailyCaptureAudit,
	): DailyCapture {
		val startedAtNanos = System.nanoTime()
		val transition = transactionExecutor.required {
			val capture = captureService.requireOwned(userId, captureId)
			val day = dayRepository.findByIdForUpdate(capture.dayId)
				?: throw DailyNotFoundException("Capture day was not found")
			if (day.userId != userId) {
				throw DailyNotFoundException.capture(captureId.value)
			}
			if (day.status == DailyDayStatus.CONFIRMED) {
				throw DailyConflictException("Confirmed day captures cannot change status")
			}
			val at = clock.instant()
			val persisted = try {
				captureRepository.save(change(capture, at))
			} catch (exception: IllegalStateException) {
				throw DailyConflictException(exception.message ?: "Invalid capture transition")
			}
			if (persisted.version <= capture.version) {
				throw IllegalStateException("Capture persistence did not increment its optimistic-lock version")
			}
			auditRepository.save(audit(capture, persisted, at))
			CaptureTransition(capture, persisted)
		}
		logger.atInfo()
			.addKeyValue("event", event)
			.addKeyValue("outcome", "success")
			.addKeyValue("userRef", userId.value)
			.addKeyValue("captureId", captureId.value)
			.addKeyValue("captureType", transition.after.captureType)
			.addKeyValue("oldStatus", transition.before.status)
			.addKeyValue("newStatus", transition.after.status)
			.addKeyValue("oldVersion", transition.before.version)
			.addKeyValue("newVersion", transition.after.version)
			.addKeyValue("durationMs", elapsedMilliseconds(startedAtNanos))
			.log(message)
		return transition.after
	}

	private data class CaptureTransition(
		val before: DailyCapture,
		val after: DailyCapture,
	)

	private companion object {
		val logger = LoggerFactory.getLogger(CaptureConfirmationService::class.java)
	}
}
