package com.fitlake.daily.domain.inbox

import com.fitlake.daily.domain.capture.DailyCaptureId
import com.fitlake.daily.domain.common.DailyDayId
import com.fitlake.user.domain.UserId
import java.time.Instant
import java.util.UUID

@JvmInline
value class DailyInboxEventId(val value: UUID)

enum class DailyInboxChannel {
	REST_AI_MESSAGE,
	REST_AI_REPROCESS,
	TELEGRAM,
	MOBILE,
}

enum class DailyInboxSourceType {
	TEXT_MESSAGE,
	VOICE_MESSAGE,
	CALLBACK,
	MOBILE_AI_INPUT,
	MOBILE_UI_ACTION,
}

enum class DailyInboxProcessingStatus {
	RECEIVED,
	PROCESSING,
	PROCESSED,
	FAILED,
	IGNORED,
}

data class DailyInboxEvent(
	val inboxEventId: DailyInboxEventId,
	val userId: UserId,
	val dayId: DailyDayId,
	val channel: DailyInboxChannel,
	val sourceType: DailyInboxSourceType,
	val sourceMessageId: String,
	val rawText: String,
	val normalizedText: String,
	val processingStatus: DailyInboxProcessingStatus,
	val errorCode: String?,
	val errorMessage: String?,
	val receivedAt: Instant,
	val processingStartedAt: Instant,
	val processingAttemptId: UUID,
	val processedAt: Instant?,
	val createdAt: Instant,
	val replacesCaptureId: DailyCaptureId?,
) {
	init {
		require(sourceMessageId.isNotBlank()) { "Inbox idempotency key must not be blank" }
		require(sourceMessageId.length <= 200) { "Inbox idempotency key must not exceed 200 characters" }
		require(rawText.isNotBlank()) { "Inbox text must not be blank" }
		require(normalizedText.isNotBlank()) { "Normalized inbox text must not be blank" }
		require(errorCode == null || errorCode.isNotBlank()) { "Inbox error code must be null or non-blank" }
		require(errorMessage == null || errorMessage.isNotBlank()) { "Inbox error message must be null or non-blank" }
		require(!processingStartedAt.isBefore(receivedAt)) {
			"Inbox processing lease cannot precede receipt"
		}
	}

	fun renewProcessing(at: Instant): DailyInboxEvent {
		check(
			processingStatus == DailyInboxProcessingStatus.RECEIVED ||
				processingStatus == DailyInboxProcessingStatus.PROCESSING,
		) {
			"Only a received or processing inbox event can renew its lease"
		}
		return copy(
			processingStatus = DailyInboxProcessingStatus.PROCESSING,
			processingStartedAt = maxOf(processingStartedAt, at),
			processingAttemptId = UUID.randomUUID(),
		)
	}

	fun processed(at: Instant): DailyInboxEvent = terminal(
		status = DailyInboxProcessingStatus.PROCESSED,
		at = at,
	)

	fun ignored(at: Instant): DailyInboxEvent = terminal(
		status = DailyInboxProcessingStatus.IGNORED,
		at = at,
	)

	fun failed(code: String, message: String, at: Instant): DailyInboxEvent = terminal(
		status = DailyInboxProcessingStatus.FAILED,
		at = at,
		errorCode = code,
		errorMessage = message,
	)

	private fun terminal(
		status: DailyInboxProcessingStatus,
		at: Instant,
		errorCode: String? = null,
		errorMessage: String? = null,
	): DailyInboxEvent {
		check(processingStatus == DailyInboxProcessingStatus.PROCESSING) {
			"Only a processing inbox event can become terminal"
		}
		return copy(
			processingStatus = status,
			errorCode = errorCode,
			errorMessage = errorMessage,
			processedAt = at,
		)
	}

	companion object {
		fun processing(
			userId: UserId,
			dayId: DailyDayId,
			channel: DailyInboxChannel,
			sourceMessageId: String,
			rawText: String,
			normalizedText: String,
			replacesCaptureId: DailyCaptureId?,
			at: Instant,
		): DailyInboxEvent = DailyInboxEvent(
			inboxEventId = DailyInboxEventId(UUID.randomUUID()),
			userId = userId,
			dayId = dayId,
			channel = channel,
			sourceType = DailyInboxSourceType.MOBILE_AI_INPUT,
			sourceMessageId = sourceMessageId,
			rawText = rawText,
			normalizedText = normalizedText,
			processingStatus = DailyInboxProcessingStatus.PROCESSING,
			errorCode = null,
			errorMessage = null,
			receivedAt = at,
			processingStartedAt = at,
			processingAttemptId = UUID.randomUUID(),
			processedAt = null,
			createdAt = at,
			replacesCaptureId = replacesCaptureId,
		)
	}
}
