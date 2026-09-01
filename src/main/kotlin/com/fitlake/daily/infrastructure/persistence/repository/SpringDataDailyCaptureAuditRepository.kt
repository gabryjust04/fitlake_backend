package com.fitlake.daily.infrastructure.persistence.repository

import com.fitlake.daily.infrastructure.persistence.entity.DailyCaptureAuditEntity
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface SpringDataDailyCaptureAuditRepository : JpaRepository<DailyCaptureAuditEntity, UUID> {
	fun findAllByCaptureIdAndUserIdOrderByCreatedAtAscAuditIdAsc(
		captureId: UUID,
		userId: UUID,
	): List<DailyCaptureAuditEntity>
}
