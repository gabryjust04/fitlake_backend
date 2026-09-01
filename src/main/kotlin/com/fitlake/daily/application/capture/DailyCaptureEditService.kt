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
class DailyCaptureEditService(
	private val dayRepository: DailyDayRepository,
	private val captureRepository: DailyCaptureRepository,
	private val auditRepository: DailyCaptureAuditRepository,
	private val captureService: DailyCaptureService,
	private val transactionExecutor: TransactionExecutor,
	private val clock: Clock,
) {
	fun softDelete(userId: UserId, captureId: DailyCaptureId): DailyCapture =
		edit(
			userId = userId,
			captureId = captureId,
			change = { capture, at -> capture.softDelete(at) },
			audit = { before, after, at ->
				DailyCaptureAudit.softDelete(
					captureId = after.captureId,
					userId = after.userId,
					oldStatus = before.status,
					oldVersion = before.version,
					newVersion = after.version,
					reasonCode = null,
					requestId = null,
					at = at,
				)
			},
		)

	private fun edit(
		userId: UserId,
		captureId: DailyCaptureId,
		change: (DailyCapture, Instant) -> DailyCapture,
		audit: (DailyCapture, DailyCapture, Instant) -> DailyCaptureAudit,
	): DailyCapture {
		val startedAtNanos = System.nanoTime()
		val edit = transactionExecutor.required {
			val capture = captureService.requireOwned(userId, captureId)
			val day = dayRepository.findByIdForUpdate(capture.dayId)
				?: throw DailyNotFoundException("Capture day was not found")
			if (day.userId != userId) {
				throw DailyNotFoundException.capture(captureId.value)
			}
			if (day.status == DailyDayStatus.CONFIRMED) {
				throw DailyConflictException("Confirmed day captures cannot be edited")
			}
			val at = clock.instant()
			val persisted = try {
				captureRepository.save(change(capture, at))
			} catch (exception: IllegalStateException) {
				throw DailyConflictException(exception.message ?: "Capture cannot be edited")
			}
			if (persisted.version <= capture.version) {
				throw IllegalStateException("Capture persistence did not increment its optimistic-lock version")
			}
			auditRepository.save(audit(capture, persisted, at))
			CaptureEdit(capture, persisted)
		}
		logger.atInfo()
			.addKeyValue("event", "daily_capture_soft_deleted")
			.addKeyValue("outcome", "success")
			.addKeyValue("userRef", userId.value)
			.addKeyValue("captureId", captureId.value)
			.addKeyValue("captureType", edit.after.captureType)
			.addKeyValue("oldStatus", edit.before.status)
			.addKeyValue("newStatus", edit.after.status)
			.addKeyValue("oldVersion", edit.before.version)
			.addKeyValue("newVersion", edit.after.version)
			.addKeyValue("durationMs", elapsedMilliseconds(startedAtNanos))
			.log("Daily capture soft deleted")
		return edit.after
	}

	private data class CaptureEdit(
		val before: DailyCapture,
		val after: DailyCapture,
	)

	private companion object {
		val logger = LoggerFactory.getLogger(DailyCaptureEditService::class.java)
	}
}
