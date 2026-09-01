package com.fitlake.daily.infrastructure.persistence.entity

import com.fitlake.daily.domain.ai.AiInterpretationStatus
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "ai_interpretation_log")
class AiInterpretationLogEntity(
	@Id
	@Column(name = "ai_log_id", nullable = false, updatable = false)
	var aiLogId: UUID,

	@Column(name = "user_id", nullable = false, updatable = false)
	var userId: UUID,

	@Column(name = "inbox_event_id", updatable = false)
	var inboxEventId: UUID?,

	@Column(name = "capture_id", updatable = false)
	var captureId: UUID?,

	@Column(name = "provider", nullable = false, length = 100, updatable = false)
	var provider: String,

	@Column(name = "model", nullable = false, length = 255, updatable = false)
	var model: String,

	@Column(name = "prompt_version", nullable = false, length = 100, updatable = false)
	var promptVersion: String,

	@Column(name = "input_text", columnDefinition = "text", updatable = false)
	var inputText: String?,

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(name = "context_snapshot", columnDefinition = "jsonb", updatable = false)
	var contextSnapshot: Map<String, Any?>?,

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(name = "parsed_output", columnDefinition = "jsonb", updatable = false)
	var parsedOutput: Map<String, Any?>?,

	@Enumerated(EnumType.STRING)
	@Column(name = "status", nullable = false, length = 30, updatable = false)
	var status: AiInterpretationStatus,

	@Column(name = "confidence", updatable = false)
	var confidence: BigDecimal?,

	@Column(name = "error_code", length = 100, updatable = false)
	var errorCode: String?,

	@Column(name = "error_message", columnDefinition = "text", updatable = false)
	var errorMessage: String?,

	@Column(name = "latency_ms", updatable = false)
	var latencyMs: Int?,

	@Column(name = "created_at", nullable = false, updatable = false)
	var createdAt: Instant,
)
