package com.fitlake.daily.infrastructure.persistence.entity

import com.fitlake.daily.domain.capture.DailyCaptureActor
import com.fitlake.daily.domain.capture.DailyCaptureStatus
import com.fitlake.daily.domain.capture.DailyCaptureType
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.Version
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "daily_capture")
class DailyCaptureEntity(
	@Id
	@Column(name = "capture_id", nullable = false, updatable = false)
	var captureId: UUID,

	@Column(name = "user_id", nullable = false, updatable = false)
	var userId: UUID,

	@Column(name = "day_id", nullable = false, updatable = false)
	var dayId: UUID,

	@Column(name = "source_event_id", updatable = false)
	var sourceEventId: UUID?,

	@Enumerated(EnumType.STRING)
	@Column(name = "capture_type", nullable = false, length = 20)
	var captureType: DailyCaptureType,

	@Enumerated(EnumType.STRING)
	@Column(name = "status", nullable = false, length = 20)
	var status: DailyCaptureStatus,

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(name = "payload", nullable = false, columnDefinition = "jsonb")
	var payload: Map<String, Any?>,

	@Column(name = "confidence")
	var confidence: BigDecimal?,

	@Enumerated(EnumType.STRING)
	@Column(name = "created_by", nullable = false, length = 20, updatable = false)
	var createdBy: DailyCaptureActor,

	@Enumerated(EnumType.STRING)
	@Column(name = "updated_by", length = 20)
	var updatedBy: DailyCaptureActor?,

	@Column(name = "accepted_at")
	var acceptedAt: Instant?,

	@Column(name = "rejected_at")
	var rejectedAt: Instant?,

	@Column(name = "deleted_at")
	var deletedAt: Instant?,

	@Column(name = "expired_at")
	var expiredAt: Instant?,

	@Column(name = "created_at", nullable = false, updatable = false)
	var createdAt: Instant,

	@Column(name = "updated_at", nullable = false)
	var updatedAt: Instant,

	@Version
	@Column(name = "version", nullable = false)
	var version: Long,
)
