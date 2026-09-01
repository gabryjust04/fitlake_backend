package com.fitlake.daily.infrastructure.persistence.mapper

import com.fitlake.daily.application.capture.DailyCapturePayloadCodec
import com.fitlake.daily.domain.audit.DailyCaptureAudit
import com.fitlake.daily.domain.audit.DailyCaptureAuditId
import com.fitlake.daily.domain.capture.DailyCaptureId
import com.fitlake.daily.infrastructure.persistence.entity.DailyCaptureAuditEntity
import com.fitlake.user.domain.UserId
import org.springframework.stereotype.Component

@Component
class DailyCaptureAuditPersistenceMapper {
	fun toDomain(entity: DailyCaptureAuditEntity): DailyCaptureAudit = DailyCaptureAudit(
		auditId = DailyCaptureAuditId(entity.auditId),
		captureId = DailyCaptureId(entity.captureId),
		userId = UserId(entity.userId),
		action = entity.action,
		actor = entity.actor,
		oldPayload = entity.oldPayload?.let(DailyCapturePayloadCodec::decode),
		newPayload = entity.newPayload?.let(DailyCapturePayloadCodec::decode),
		oldStatus = entity.oldStatus,
		newStatus = entity.newStatus,
		oldVersion = entity.oldVersion,
		newVersion = entity.newVersion,
		reasonCode = entity.reasonCode,
		relatedCaptureId = entity.relatedCaptureId?.let(::DailyCaptureId),
		requestId = entity.requestId,
		createdAt = entity.createdAt,
	)

	fun toEntity(domain: DailyCaptureAudit): DailyCaptureAuditEntity = DailyCaptureAuditEntity(
		auditId = domain.auditId.value,
		captureId = domain.captureId.value,
		userId = domain.userId.value,
		action = domain.action,
		actor = domain.actor,
		oldPayload = domain.oldPayload?.let(DailyCapturePayloadCodec::encode),
		newPayload = domain.newPayload?.let(DailyCapturePayloadCodec::encode),
		oldStatus = domain.oldStatus,
		newStatus = domain.newStatus,
		oldVersion = domain.oldVersion,
		newVersion = domain.newVersion,
		reasonCode = domain.reasonCode,
		relatedCaptureId = domain.relatedCaptureId?.value,
		requestId = domain.requestId,
		createdAt = domain.createdAt,
	)
}
