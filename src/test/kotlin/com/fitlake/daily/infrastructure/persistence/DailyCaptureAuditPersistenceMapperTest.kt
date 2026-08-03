package com.fitlake.daily.infrastructure.persistence

import com.fitlake.daily.domain.audit.DailyCaptureAudit
import com.fitlake.daily.domain.capture.DailyCaptureActor
import com.fitlake.daily.domain.capture.DailyCaptureId
import com.fitlake.daily.infrastructure.persistence.mapper.DailyCaptureAuditPersistenceMapper
import com.fitlake.support.dailyFieldsPayload
import com.fitlake.user.domain.UserId
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DailyCaptureAuditPersistenceMapperTest {
	private val mapper = DailyCaptureAuditPersistenceMapper()
	private val at = Instant.parse("2026-07-31T10:00:00Z")

	@Test
	fun `create round trip preserves nullable old state and version zero`() {
		val audit = DailyCaptureAudit.create(
			captureId = DailyCaptureId(UUID.randomUUID()),
			userId = UserId(UUID.randomUUID()),
			newPayload = dailyFieldsPayload(bodyWeightKg = BigDecimal("78.4")),
			actor = DailyCaptureActor.USER_UI,
			requestId = null,
			at = at,
		)

		val entity = mapper.toEntity(audit)

		assertNull(entity.oldPayload)
		assertNull(entity.oldStatus)
		assertNull(entity.oldVersion)
		assertEquals(0, entity.newVersion)
		assertEquals(audit, mapper.toDomain(entity))
	}

	@Test
	fun `reprocess round trip preserves status transition reason and capture linkage`() {
		val audit = DailyCaptureAudit.replacedByReprocess(
			captureId = DailyCaptureId(UUID.randomUUID()),
			userId = UserId(UUID.randomUUID()),
			relatedCaptureId = DailyCaptureId(UUID.randomUUID()),
			oldVersion = 3,
			newVersion = 4,
			requestId = "reprocess-1",
			at = at,
		)

		val entity = mapper.toEntity(audit)

		assertNull(entity.oldPayload)
		assertNull(entity.newPayload)
		assertEquals(audit.reasonCode, entity.reasonCode)
		assertEquals(audit.relatedCaptureId?.value, entity.relatedCaptureId)
		assertEquals(audit, mapper.toDomain(entity))
	}
}
