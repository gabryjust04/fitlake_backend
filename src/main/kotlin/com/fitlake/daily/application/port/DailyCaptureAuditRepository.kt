package com.fitlake.daily.application.port

import com.fitlake.daily.domain.audit.DailyCaptureAudit
import com.fitlake.daily.domain.capture.DailyCaptureId
import com.fitlake.user.domain.UserId

interface DailyCaptureAuditRepository {
	fun save(audit: DailyCaptureAudit): DailyCaptureAudit

	fun findAllByCaptureIdAndUserId(
		captureId: DailyCaptureId,
		userId: UserId,
	): List<DailyCaptureAudit>
}
