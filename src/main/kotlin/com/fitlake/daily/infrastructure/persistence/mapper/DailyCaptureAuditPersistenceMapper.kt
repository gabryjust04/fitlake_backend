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
		oldPayload = DailyCapturePayloadCodec.decode(entity.oldPayload),
		newPayload = DailyCapturePayloadCodec.decode(entity.newPayload),
		oldVersion = entity.oldVersion,
		newVersion = entity.newVersion,
		requestId = entity.requestId,
		createdAt = entity.createdAt,
	)

	fun toEntity(domain: DailyCaptureAudit): DailyCaptureAuditEntity = DailyCaptureAuditEntity(
		auditId = domain.auditId.value,
		captureId = domain.captureId.value,
		userId = domain.userId.value,
		action = domain.action,
		oldPayload = DailyCapturePayloadCodec.encode(domain.oldPayload),
		newPayload = DailyCapturePayloadCodec.encode(domain.newPayload),
		oldVersion = domain.oldVersion,
		newVersion = domain.newVersion,
		requestId = domain.requestId,
		createdAt = domain.createdAt,
	)
}
