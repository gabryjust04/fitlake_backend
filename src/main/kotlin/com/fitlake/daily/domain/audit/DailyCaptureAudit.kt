package com.fitlake.daily.domain.audit

import com.fitlake.daily.domain.capture.DailyCaptureId
import com.fitlake.daily.domain.capture.DailyCapturePayload
import com.fitlake.user.domain.UserId
import java.time.Instant
import java.util.UUID

@JvmInline
value class DailyCaptureAuditId(val value: UUID)

enum class DailyCaptureAuditAction {
	UI_EDIT,
}

data class DailyCaptureAudit(
	val auditId: DailyCaptureAuditId,
	val captureId: DailyCaptureId,
	val userId: UserId,
	val action: DailyCaptureAuditAction,
	val oldPayload: DailyCapturePayload,
	val newPayload: DailyCapturePayload,
	val oldVersion: Long,
	val newVersion: Long,
	val requestId: String?,
	val createdAt: Instant,
) {
	init {
		require(oldVersion >= 0) { "Old capture version must not be negative" }
		require(newVersion > oldVersion) { "New capture version must be greater than the old version" }
		require(requestId == null || requestId.isNotBlank()) { "Audit request ID must be null or non-blank" }
		require(requestId == null || requestId.length <= 100) { "Audit request ID is too long" }
	}

	companion object {
		fun uiEdit(
			captureId: DailyCaptureId,
			userId: UserId,
			oldPayload: DailyCapturePayload,
			newPayload: DailyCapturePayload,
			oldVersion: Long,
			newVersion: Long,
			requestId: String?,
			at: Instant,
		): DailyCaptureAudit = DailyCaptureAudit(
			auditId = DailyCaptureAuditId(UUID.randomUUID()),
			captureId = captureId,
			userId = userId,
			action = DailyCaptureAuditAction.UI_EDIT,
			oldPayload = oldPayload,
			newPayload = newPayload,
			oldVersion = oldVersion,
			newVersion = newVersion,
			requestId = requestId,
			createdAt = at,
		)
	}
}
