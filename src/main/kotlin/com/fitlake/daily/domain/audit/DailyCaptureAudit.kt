package com.fitlake.daily.domain.audit

import com.fitlake.daily.domain.capture.DailyCaptureActor
import com.fitlake.daily.domain.capture.DailyCaptureId
import com.fitlake.daily.domain.capture.DailyCapturePayload
import com.fitlake.daily.domain.capture.DailyCaptureStatus
import com.fitlake.user.domain.UserId
import java.time.Instant
import java.util.UUID

@JvmInline
value class DailyCaptureAuditId(val value: UUID)

enum class DailyCaptureAuditAction {
	CREATE,
	ACCEPT,
	REJECT,
	UI_EDIT,
	SOFT_DELETE,
	REPLACED_BY_REPROCESS,
}

data class DailyCaptureAudit(
	val auditId: DailyCaptureAuditId,
	val captureId: DailyCaptureId,
	val userId: UserId,
	val action: DailyCaptureAuditAction,
	val actor: DailyCaptureActor,
	val oldPayload: DailyCapturePayload?,
	val newPayload: DailyCapturePayload?,
	val oldStatus: DailyCaptureStatus?,
	val newStatus: DailyCaptureStatus?,
	val oldVersion: Long?,
	val newVersion: Long?,
	val reasonCode: String?,
	val relatedCaptureId: DailyCaptureId?,
	val requestId: String?,
	val createdAt: Instant,
) {
	init {
		require(oldVersion == null || oldVersion >= 0) { "Old capture version must not be negative" }
		require(newVersion == null || newVersion >= 0) { "New capture version must not be negative" }
		require(oldVersion == null || newVersion == null || newVersion > oldVersion) {
			"New capture version must be greater than the old version"
		}
		require(reasonCode == null || reasonCode.isNotBlank()) { "Audit reason code must be null or non-blank" }
		require(reasonCode == null || reasonCode.length <= MAX_METADATA_LENGTH) { "Audit reason code is too long" }
		require(requestId == null || requestId.isNotBlank()) { "Audit request ID must be null or non-blank" }
		require(requestId == null || requestId.length <= MAX_METADATA_LENGTH) { "Audit request ID is too long" }
		require(relatedCaptureId == null || relatedCaptureId != captureId) {
			"Related capture must differ from the audited capture"
		}

		when (action) {
			DailyCaptureAuditAction.CREATE -> {
				require(actor == DailyCaptureActor.AI || actor == DailyCaptureActor.USER_UI) {
					"Capture creation must be attributed to AI or USER_UI"
				}
				require(oldPayload == null && newPayload != null) { "Capture creation requires only a new payload" }
				require(oldStatus == null && newStatus == DailyCaptureStatus.OPEN) {
					"Capture creation must transition from no status to OPEN"
				}
				require(oldVersion == null && newVersion == INITIAL_CAPTURE_VERSION) {
					"Capture creation must record initial version 0"
				}
				require(reasonCode == null && relatedCaptureId == null) {
					"Capture creation cannot have a reason or related capture"
				}
			}

			DailyCaptureAuditAction.ACCEPT -> requireLifecycleTransition(
				expectedActor = DailyCaptureActor.USER_UI,
				expectedOldStatus = DailyCaptureStatus.OPEN,
				expectedNewStatus = DailyCaptureStatus.ACCEPTED,
				allowReason = false,
				allowRelatedCapture = false,
			)

			DailyCaptureAuditAction.REJECT -> requireLifecycleTransition(
				expectedActor = DailyCaptureActor.USER_UI,
				expectedOldStatus = DailyCaptureStatus.OPEN,
				expectedNewStatus = DailyCaptureStatus.REJECTED,
				allowReason = true,
				allowRelatedCapture = false,
			)

			DailyCaptureAuditAction.UI_EDIT -> {
				require(actor == DailyCaptureActor.USER_UI) { "UI edit must be attributed to USER_UI" }
				require(oldPayload != null && newPayload != null) { "UI edit requires old and new payloads" }
				require(
					(oldStatus == null && newStatus == null) ||
						(oldStatus != null && oldStatus in EDITABLE_STATUSES && newStatus == oldStatus),
				) { "UI edit must preserve an optional editable capture status" }
				require(oldVersion != null && newVersion != null) { "UI edit requires old and new versions" }
				require(reasonCode == null && relatedCaptureId == null) {
					"UI edit cannot have a reason or related capture"
				}
			}

			DailyCaptureAuditAction.SOFT_DELETE -> {
				require(actor == DailyCaptureActor.USER_UI) { "Soft delete must be attributed to USER_UI" }
				require(oldPayload == null && newPayload == null) { "Soft delete does not record payload copies" }
				require(oldStatus != null && oldStatus in DELETABLE_STATUSES && newStatus == DailyCaptureStatus.SOFT_DELETED) {
					"Soft delete must transition an editable status to SOFT_DELETED"
				}
				require(oldVersion != null && newVersion != null) { "Soft delete requires old and new versions" }
				require(relatedCaptureId == null) { "Soft delete cannot have a related capture" }
			}

			DailyCaptureAuditAction.REPLACED_BY_REPROCESS -> {
				requireLifecycleTransition(
					expectedActor = DailyCaptureActor.SYSTEM,
					expectedOldStatus = DailyCaptureStatus.OPEN,
					expectedNewStatus = DailyCaptureStatus.REJECTED,
					allowReason = true,
					allowRelatedCapture = true,
				)
				require(reasonCode == REPROCESS_REASON_CODE) {
					"Reprocess replacement requires reason REPLACED_BY_REPROCESS"
				}
				require(relatedCaptureId != null) { "Reprocess replacement requires the new capture ID" }
			}
		}
	}

	private fun requireLifecycleTransition(
		expectedActor: DailyCaptureActor,
		expectedOldStatus: DailyCaptureStatus,
		expectedNewStatus: DailyCaptureStatus,
		allowReason: Boolean,
		allowRelatedCapture: Boolean,
	) {
		require(actor == expectedActor) { "$action has an invalid actor" }
		require(oldPayload == null && newPayload == null) { "$action does not record payload copies" }
		require(oldStatus == expectedOldStatus && newStatus == expectedNewStatus) {
			"$action has an invalid status transition"
		}
		require(oldVersion != null && newVersion != null) { "$action requires old and new versions" }
		require(allowReason || reasonCode == null) { "$action cannot have a reason code" }
		require(allowRelatedCapture || relatedCaptureId == null) { "$action cannot have a related capture" }
	}

	companion object {
		private const val INITIAL_CAPTURE_VERSION = 0L
		private const val MAX_METADATA_LENGTH = 100
		const val REPROCESS_REASON_CODE = "REPLACED_BY_REPROCESS"
		private val EDITABLE_STATUSES = setOf(DailyCaptureStatus.OPEN, DailyCaptureStatus.ACCEPTED)
		private val DELETABLE_STATUSES = setOf(
			DailyCaptureStatus.OPEN,
			DailyCaptureStatus.ACCEPTED,
			DailyCaptureStatus.REJECTED,
		)

		fun create(
			captureId: DailyCaptureId,
			userId: UserId,
			newPayload: DailyCapturePayload,
			actor: DailyCaptureActor,
			requestId: String?,
			at: Instant,
			newVersion: Long = INITIAL_CAPTURE_VERSION,
			newStatus: DailyCaptureStatus = DailyCaptureStatus.OPEN,
		): DailyCaptureAudit = newAudit(
			captureId = captureId,
			userId = userId,
			action = DailyCaptureAuditAction.CREATE,
			actor = actor,
			newPayload = newPayload,
			newStatus = newStatus,
			newVersion = newVersion,
			requestId = requestId,
			at = at,
		)

		fun accept(
			captureId: DailyCaptureId,
			userId: UserId,
			oldVersion: Long,
			newVersion: Long,
			requestId: String?,
			at: Instant,
		): DailyCaptureAudit = newAudit(
			captureId = captureId,
			userId = userId,
			action = DailyCaptureAuditAction.ACCEPT,
			actor = DailyCaptureActor.USER_UI,
			oldStatus = DailyCaptureStatus.OPEN,
			newStatus = DailyCaptureStatus.ACCEPTED,
			oldVersion = oldVersion,
			newVersion = newVersion,
			requestId = requestId,
			at = at,
		)

		fun reject(
			captureId: DailyCaptureId,
			userId: UserId,
			oldVersion: Long,
			newVersion: Long,
			reasonCode: String?,
			requestId: String?,
			at: Instant,
		): DailyCaptureAudit = newAudit(
			captureId = captureId,
			userId = userId,
			action = DailyCaptureAuditAction.REJECT,
			actor = DailyCaptureActor.USER_UI,
			oldStatus = DailyCaptureStatus.OPEN,
			newStatus = DailyCaptureStatus.REJECTED,
			oldVersion = oldVersion,
			newVersion = newVersion,
			reasonCode = reasonCode,
			requestId = requestId,
			at = at,
		)

		fun uiEdit(
			captureId: DailyCaptureId,
			userId: UserId,
			oldPayload: DailyCapturePayload,
			newPayload: DailyCapturePayload,
			oldVersion: Long,
			newVersion: Long,
			requestId: String?,
			at: Instant,
			status: DailyCaptureStatus? = null,
		): DailyCaptureAudit = newAudit(
			captureId = captureId,
			userId = userId,
			action = DailyCaptureAuditAction.UI_EDIT,
			actor = DailyCaptureActor.USER_UI,
			oldPayload = oldPayload,
			newPayload = newPayload,
			oldStatus = status,
			newStatus = status,
			oldVersion = oldVersion,
			newVersion = newVersion,
			requestId = requestId,
			at = at,
		)

		fun softDelete(
			captureId: DailyCaptureId,
			userId: UserId,
			oldStatus: DailyCaptureStatus,
			oldVersion: Long,
			newVersion: Long,
			reasonCode: String?,
			requestId: String?,
			at: Instant,
		): DailyCaptureAudit = newAudit(
			captureId = captureId,
			userId = userId,
			action = DailyCaptureAuditAction.SOFT_DELETE,
			actor = DailyCaptureActor.USER_UI,
			oldStatus = oldStatus,
			newStatus = DailyCaptureStatus.SOFT_DELETED,
			oldVersion = oldVersion,
			newVersion = newVersion,
			reasonCode = reasonCode,
			requestId = requestId,
			at = at,
		)

		fun replacedByReprocess(
			captureId: DailyCaptureId,
			userId: UserId,
			relatedCaptureId: DailyCaptureId,
			oldVersion: Long,
			newVersion: Long,
			requestId: String?,
			at: Instant,
		): DailyCaptureAudit = newAudit(
			captureId = captureId,
			userId = userId,
			action = DailyCaptureAuditAction.REPLACED_BY_REPROCESS,
			actor = DailyCaptureActor.SYSTEM,
			oldStatus = DailyCaptureStatus.OPEN,
			newStatus = DailyCaptureStatus.REJECTED,
			oldVersion = oldVersion,
			newVersion = newVersion,
			reasonCode = REPROCESS_REASON_CODE,
			relatedCaptureId = relatedCaptureId,
			requestId = requestId,
			at = at,
		)

		private fun newAudit(
			captureId: DailyCaptureId,
			userId: UserId,
			action: DailyCaptureAuditAction,
			actor: DailyCaptureActor,
			oldPayload: DailyCapturePayload? = null,
			newPayload: DailyCapturePayload? = null,
			oldStatus: DailyCaptureStatus? = null,
			newStatus: DailyCaptureStatus? = null,
			oldVersion: Long? = null,
			newVersion: Long? = null,
			reasonCode: String? = null,
			relatedCaptureId: DailyCaptureId? = null,
			requestId: String?,
			at: Instant,
		): DailyCaptureAudit = DailyCaptureAudit(
			auditId = DailyCaptureAuditId(UUID.randomUUID()),
			captureId = captureId,
			userId = userId,
			action = action,
			actor = actor,
			oldPayload = oldPayload,
			newPayload = newPayload,
			oldStatus = oldStatus,
			newStatus = newStatus,
			oldVersion = oldVersion,
			newVersion = newVersion,
			reasonCode = reasonCode,
			relatedCaptureId = relatedCaptureId,
			requestId = requestId,
			createdAt = at,
		)
	}
}
