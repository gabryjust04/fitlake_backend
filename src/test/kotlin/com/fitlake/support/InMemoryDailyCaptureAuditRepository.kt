package com.fitlake.support

import com.fitlake.daily.application.port.DailyCaptureAuditRepository
import com.fitlake.daily.domain.audit.DailyCaptureAudit
import com.fitlake.daily.domain.audit.DailyCaptureAuditId
import com.fitlake.daily.domain.capture.DailyCaptureId
import com.fitlake.user.domain.UserId
import java.util.concurrent.ConcurrentHashMap

class InMemoryDailyCaptureAuditRepository : DailyCaptureAuditRepository {
	private val audits = ConcurrentHashMap<DailyCaptureAuditId, DailyCaptureAudit>()

	@Synchronized
	override fun save(audit: DailyCaptureAudit): DailyCaptureAudit {
		require(audits.putIfAbsent(audit.auditId, audit) == null) { "Duplicate capture audit ID" }
		return audit
	}

	override fun findAllByCaptureIdAndUserId(
		captureId: DailyCaptureId,
		userId: UserId,
	): List<DailyCaptureAudit> = audits.values
		.filter { it.captureId == captureId && it.userId == userId }
		.sortedWith(compareBy<DailyCaptureAudit> { it.createdAt }.thenBy { it.auditId.value })

	fun all(): List<DailyCaptureAudit> = audits.values
		.sortedWith(compareBy<DailyCaptureAudit> { it.createdAt }.thenBy { it.auditId.value })

	fun count(): Int = audits.size

	fun clear() = audits.clear()
}
