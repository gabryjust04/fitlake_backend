package com.fitlake.daily.domain

import com.fitlake.daily.domain.audit.DailyCaptureAudit
import com.fitlake.daily.domain.audit.DailyCaptureAuditAction
import com.fitlake.daily.domain.capture.DailyCaptureId
import com.fitlake.support.dailyFieldsPayload
import com.fitlake.user.domain.UserId
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class DailyCaptureAuditTest {
	private val payload = dailyFieldsPayload(bodyWeightKg = BigDecimal("78.4"))

	@Test
	fun `UI edit factory owns audit identity and metadata`() {
		val captureId = DailyCaptureId(UUID.randomUUID())
		val userId = UserId(UUID.randomUUID())
		val at = Instant.parse("2026-07-31T10:00:00Z")

		val audit = DailyCaptureAudit.uiEdit(
			captureId = captureId,
			userId = userId,
			oldPayload = payload,
			newPayload = payload,
			oldVersion = 4,
			newVersion = 5,
			requestId = "request-1",
			at = at,
		)

		assertEquals(captureId, audit.captureId)
		assertEquals(userId, audit.userId)
		assertEquals(DailyCaptureAuditAction.UI_EDIT, audit.action)
		assertEquals(4, audit.oldVersion)
		assertEquals(5, audit.newVersion)
		assertEquals(at, audit.createdAt)
	}

	@Test
	fun `audit rejects invalid version transitions`() {
		assertFailsWith<IllegalArgumentException> {
			DailyCaptureAudit.uiEdit(
				captureId = DailyCaptureId(UUID.randomUUID()),
				userId = UserId(UUID.randomUUID()),
				oldPayload = payload,
				newPayload = payload,
				oldVersion = 5,
				newVersion = 5,
				requestId = null,
				at = Instant.parse("2026-07-31T10:00:00Z"),
			)
		}
	}
}
