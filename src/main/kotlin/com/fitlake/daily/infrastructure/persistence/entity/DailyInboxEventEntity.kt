package com.fitlake.daily.infrastructure.persistence.entity

import com.fitlake.daily.domain.inbox.DailyInboxChannel
import com.fitlake.daily.domain.inbox.DailyInboxProcessingStatus
import com.fitlake.daily.domain.inbox.DailyInboxSourceType
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "daily_inbox_event")
class DailyInboxEventEntity(
	@Id
	@Column(name = "inbox_event_id", nullable = false, updatable = false)
	var inboxEventId: UUID,

	@Column(name = "user_id", nullable = false, updatable = false)
	var userId: UUID,

	@Column(name = "day_id", updatable = false)
	var dayId: UUID?,

	@Enumerated(EnumType.STRING)
	@Column(name = "channel", nullable = false, length = 50, updatable = false)
	var channel: DailyInboxChannel,

	@Enumerated(EnumType.STRING)
	@Column(name = "source_type", nullable = false, length = 30, updatable = false)
	var sourceType: DailyInboxSourceType,

	@Column(name = "source_message_id", length = 255, updatable = false)
	var sourceMessageId: String?,

	@Column(name = "raw_text", columnDefinition = "text", updatable = false)
	var rawText: String?,

	@Column(name = "normalized_text", columnDefinition = "text", updatable = false)
	var normalizedText: String?,

	@Enumerated(EnumType.STRING)
	@Column(name = "processing_status", nullable = false, length = 20)
	var processingStatus: DailyInboxProcessingStatus,

	@Column(name = "error_code", length = 100)
	var errorCode: String?,

	@Column(name = "error_message", columnDefinition = "text")
	var errorMessage: String?,

	@Column(name = "received_at", nullable = false, updatable = false)
	var receivedAt: Instant,

	@Column(name = "processing_started_at", nullable = false)
	var processingStartedAt: Instant,

	@Column(name = "processing_attempt_id", nullable = false)
	var processingAttemptId: UUID,

	@Column(name = "processed_at")
	var processedAt: Instant?,

	@Column(name = "created_at", nullable = false, updatable = false)
	var createdAt: Instant,

	@Column(name = "replaces_capture_id", updatable = false)
	var replacesCaptureId: UUID?,
)
