package com.fitlake.daily.infrastructure.persistence.entity

import com.fitlake.daily.domain.audit.DailyCaptureAuditAction
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.Immutable
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.time.Instant
import java.util.UUID

@Entity
@Immutable
@Table(name = "daily_capture_audit")
class DailyCaptureAuditEntity(
	@Id
	@Column(name = "audit_id", nullable = false, updatable = false)
	var auditId: UUID,

	@Column(name = "capture_id", nullable = false, updatable = false)
	var captureId: UUID,

	@Column(name = "user_id", nullable = false, updatable = false)
	var userId: UUID,

	@Enumerated(EnumType.STRING)
	@Column(name = "action", nullable = false, length = 30, updatable = false)
	var action: DailyCaptureAuditAction,

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(name = "old_payload", nullable = false, updatable = false, columnDefinition = "jsonb")
	var oldPayload: Map<String, Any?>,

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(name = "new_payload", nullable = false, updatable = false, columnDefinition = "jsonb")
	var newPayload: Map<String, Any?>,

	@Column(name = "old_version", nullable = false, updatable = false)
	var oldVersion: Long,

	@Column(name = "new_version", nullable = false, updatable = false)
	var newVersion: Long,

	@Column(name = "request_id", length = 100, updatable = false)
	var requestId: String?,

	@Column(name = "created_at", nullable = false, updatable = false)
	var createdAt: Instant,
)
