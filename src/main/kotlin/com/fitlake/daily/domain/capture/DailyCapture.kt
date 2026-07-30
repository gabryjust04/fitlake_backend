package com.fitlake.daily.domain.capture

import com.fitlake.daily.domain.common.DailyDayId
import com.fitlake.user.domain.UserId
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

@JvmInline
value class DailyCaptureId(val value: UUID)

enum class DailyCaptureStatus {
	OPEN,
	ACCEPTED,
	REJECTED,
	SOFT_DELETED,
	EXPIRED,
}

enum class DailyCaptureActor {
	AI,
	USER_UI,
	SYSTEM,
}

data class DailyCapture(
	val captureId: DailyCaptureId,
	val userId: UserId,
	val dayId: DailyDayId,
	val sourceEventId: UUID?,
	val captureType: DailyCaptureType,
	val status: DailyCaptureStatus,
	val payload: DailyCapturePayload,
	val confidence: BigDecimal?,
	val createdBy: DailyCaptureActor,
	val updatedBy: DailyCaptureActor?,
	val acceptedAt: Instant?,
	val rejectedAt: Instant?,
	val deletedAt: Instant?,
	val expiredAt: Instant?,
	val createdAt: Instant,
	val updatedAt: Instant,
	val version: Long,
) {
	init {
		require(captureType == payload.type) { "Capture type must match payload type" }
		require(confidence == null || confidence in BigDecimal.ZERO..BigDecimal.ONE) {
			"Capture confidence must be between 0 and 1"
		}
		require(version >= 0) { "Capture version must not be negative" }
		require(!updatedAt.isBefore(createdAt)) { "Capture update cannot precede creation" }
	}

	fun accept(at: Instant): DailyCapture {
		check(status == DailyCaptureStatus.OPEN) { "Only an open capture can be accepted" }
		return copy(
			status = DailyCaptureStatus.ACCEPTED,
			acceptedAt = at,
			updatedBy = DailyCaptureActor.USER_UI,
			updatedAt = maxOf(updatedAt, at),
		)
	}

	fun reject(at: Instant): DailyCapture {
		check(status == DailyCaptureStatus.OPEN) { "Only an open capture can be rejected" }
		return copy(
			status = DailyCaptureStatus.REJECTED,
			rejectedAt = at,
			updatedBy = DailyCaptureActor.USER_UI,
			updatedAt = maxOf(updatedAt, at),
		)
	}

	fun replaceByReprocess(at: Instant): DailyCapture {
		check(status == DailyCaptureStatus.OPEN) { "Only an open capture can be replaced" }
		return copy(
			status = DailyCaptureStatus.REJECTED,
			rejectedAt = at,
			updatedBy = DailyCaptureActor.SYSTEM,
			updatedAt = maxOf(updatedAt, at),
		)
	}

	fun replacePayload(newPayload: DailyCapturePayload, at: Instant): DailyCapture {
		check(status == DailyCaptureStatus.OPEN || status == DailyCaptureStatus.ACCEPTED) {
			"Only open or accepted captures can be edited"
		}
		return copy(
			captureType = newPayload.type,
			payload = newPayload,
			updatedBy = DailyCaptureActor.USER_UI,
			updatedAt = maxOf(updatedAt, at),
		)
	}

	fun updateFoodItem(itemTempId: String, quantity: BigDecimal, unit: String, at: Instant): DailyCapture =
		replacePayload(payload.updateFoodItem(itemTempId, quantity, unit), at)

	fun softDelete(at: Instant): DailyCapture {
		check(status != DailyCaptureStatus.SOFT_DELETED && status != DailyCaptureStatus.EXPIRED) {
			"Capture cannot be soft deleted from its current state"
		}
		return copy(
			status = DailyCaptureStatus.SOFT_DELETED,
			deletedAt = at,
			updatedBy = DailyCaptureActor.USER_UI,
			updatedAt = maxOf(updatedAt, at),
		)
	}

	companion object {
		fun openFromUser(
			userId: UserId,
			dayId: DailyDayId,
			payload: DailyCapturePayload,
			at: Instant,
		): DailyCapture = DailyCapture(
			captureId = DailyCaptureId(UUID.randomUUID()),
			userId = userId,
			dayId = dayId,
			sourceEventId = null,
			captureType = payload.type,
			status = DailyCaptureStatus.OPEN,
			payload = payload,
			confidence = null,
			createdBy = DailyCaptureActor.USER_UI,
			updatedBy = null,
			acceptedAt = null,
			rejectedAt = null,
			deletedAt = null,
			expiredAt = null,
			createdAt = at,
			updatedAt = at,
			version = 0,
		)

		fun openFromAi(
			userId: UserId,
			dayId: DailyDayId,
			sourceEventId: UUID,
			payload: DailyCapturePayload,
			confidence: BigDecimal?,
			at: Instant,
		): DailyCapture = DailyCapture(
			captureId = DailyCaptureId(UUID.randomUUID()),
			userId = userId,
			dayId = dayId,
			sourceEventId = sourceEventId,
			captureType = payload.type,
			status = DailyCaptureStatus.OPEN,
			payload = payload,
			confidence = confidence,
			createdBy = DailyCaptureActor.AI,
			updatedBy = null,
			acceptedAt = null,
			rejectedAt = null,
			deletedAt = null,
			expiredAt = null,
			createdAt = at,
			updatedAt = at,
			version = 0,
		)
	}
}
