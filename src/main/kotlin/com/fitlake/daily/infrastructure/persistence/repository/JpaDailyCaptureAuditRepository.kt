package com.fitlake.daily.infrastructure.persistence.repository

import com.fitlake.daily.application.port.DailyCaptureAuditRepository
import com.fitlake.daily.domain.audit.DailyCaptureAudit
import com.fitlake.daily.domain.capture.DailyCaptureId
import com.fitlake.daily.infrastructure.persistence.mapper.DailyCaptureAuditPersistenceMapper
import com.fitlake.user.domain.UserId
import org.springframework.stereotype.Repository

@Repository
class JpaDailyCaptureAuditRepository(
	private val repository: SpringDataDailyCaptureAuditRepository,
	private val mapper: DailyCaptureAuditPersistenceMapper,
) : DailyCaptureAuditRepository {
	override fun save(audit: DailyCaptureAudit): DailyCaptureAudit =
		mapper.toDomain(repository.saveAndFlush(mapper.toEntity(audit)))

	override fun findAllByCaptureIdAndUserId(
		captureId: DailyCaptureId,
		userId: UserId,
	): List<DailyCaptureAudit> = repository
		.findAllByCaptureIdAndUserIdOrderByCreatedAtAscAuditIdAsc(captureId.value, userId.value)
		.map(mapper::toDomain)
}
