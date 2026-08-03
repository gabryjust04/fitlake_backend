package com.fitlake.daily.domain

import com.fitlake.daily.domain.audit.DailyCaptureAudit
import com.fitlake.daily.domain.audit.DailyCaptureAuditAction
import com.fitlake.daily.domain.capture.DailyCaptureActor
import com.fitlake.daily.domain.capture.DailyCaptureId
import com.fitlake.daily.domain.capture.DailyCaptureStatus
import com.fitlake.support.dailyFieldsPayload
import com.fitlake.user.domain.UserId
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame

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
			status = DailyCaptureStatus.OPEN,
		)

		assertEquals(captureId, audit.captureId)
		assertEquals(userId, audit.userId)
		assertEquals(DailyCaptureAuditAction.UI_EDIT, audit.action)
		assertEquals(DailyCaptureActor.USER_UI, audit.actor)
		assertSame(payload, audit.oldPayload)
		assertSame(payload, audit.newPayload)
		assertEquals(DailyCaptureStatus.OPEN, audit.oldStatus)
		assertEquals(DailyCaptureStatus.OPEN, audit.newStatus)
		assertEquals(4, audit.oldVersion)
		assertEquals(5, audit.newVersion)
		assertEquals(at, audit.createdAt)
	}

	@Test
	fun `create records nullable old state and initial version zero`() {
		val audit = DailyCaptureAudit.create(
			captureId = DailyCaptureId(UUID.randomUUID()),
			userId = UserId(UUID.randomUUID()),
			newPayload = payload,
			actor = DailyCaptureActor.AI,
			requestId = "message-1",
			at = Instant.parse("2026-07-31T10:00:00Z"),
		)

		assertEquals(DailyCaptureAuditAction.CREATE, audit.action)
		assertEquals(DailyCaptureActor.AI, audit.actor)
		assertNull(audit.oldPayload)
		assertSame(payload, audit.newPayload)
		assertNull(audit.oldStatus)
		assertEquals(DailyCaptureStatus.OPEN, audit.newStatus)
		assertNull(audit.oldVersion)
		assertEquals(0, audit.newVersion)
		assertNull(audit.reasonCode)
		assertNull(audit.relatedCaptureId)
	}

	@Test
	fun `reprocess replacement records lifecycle transition and related capture`() {
		val oldCaptureId = DailyCaptureId(UUID.randomUUID())
		val replacementCaptureId = DailyCaptureId(UUID.randomUUID())
		val audit = DailyCaptureAudit.replacedByReprocess(
			captureId = oldCaptureId,
			userId = UserId(UUID.randomUUID()),
			relatedCaptureId = replacementCaptureId,
			oldVersion = 0,
			newVersion = 1,
			requestId = "reprocess-1",
			at = Instant.parse("2026-07-31T10:00:00Z"),
		)

		assertEquals(DailyCaptureAuditAction.REPLACED_BY_REPROCESS, audit.action)
		assertEquals(DailyCaptureActor.SYSTEM, audit.actor)
		assertEquals(DailyCaptureStatus.OPEN, audit.oldStatus)
		assertEquals(DailyCaptureStatus.REJECTED, audit.newStatus)
		assertEquals(DailyCaptureAudit.REPROCESS_REASON_CODE, audit.reasonCode)
		assertEquals(replacementCaptureId, audit.relatedCaptureId)
		assertNull(audit.oldPayload)
		assertNull(audit.newPayload)
	}

	@Test
	fun `user lifecycle factories record explicit status transitions`() {
		val captureId = DailyCaptureId(UUID.randomUUID())
		val userId = UserId(UUID.randomUUID())
		val at = Instant.parse("2026-07-31T10:00:00Z")

		val accepted = DailyCaptureAudit.accept(captureId, userId, 0, 1, null, at)
		val rejected = DailyCaptureAudit.reject(captureId, userId, 1, 2, "USER_REJECTED", null, at)
		val deleted = DailyCaptureAudit.softDelete(
			captureId,
			userId,
			DailyCaptureStatus.ACCEPTED,
			2,
			3,
			"USER_DELETED",
			null,
			at,
		)

		assertEquals(DailyCaptureStatus.OPEN, accepted.oldStatus)
		assertEquals(DailyCaptureStatus.ACCEPTED, accepted.newStatus)
		assertEquals(DailyCaptureStatus.OPEN, rejected.oldStatus)
		assertEquals(DailyCaptureStatus.REJECTED, rejected.newStatus)
		assertEquals("USER_REJECTED", rejected.reasonCode)
		assertEquals(DailyCaptureStatus.ACCEPTED, deleted.oldStatus)
		assertEquals(DailyCaptureStatus.SOFT_DELETED, deleted.newStatus)
		assertEquals(DailyCaptureActor.USER_UI, deleted.actor)
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

	@Test
	fun `reprocess replacement rejects a self reference`() {
		val captureId = DailyCaptureId(UUID.randomUUID())

		assertFailsWith<IllegalArgumentException> {
			DailyCaptureAudit.replacedByReprocess(
				captureId = captureId,
				userId = UserId(UUID.randomUUID()),
				relatedCaptureId = captureId,
				oldVersion = 0,
				newVersion = 1,
				requestId = null,
				at = Instant.parse("2026-07-31T10:00:00Z"),
			)
		}
	}
}
