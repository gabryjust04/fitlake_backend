package com.fitlake.daily.application

import ch.qos.logback.classic.spi.ILoggingEvent
import com.fitlake.daily.application.capture.CaptureConfirmationService
import com.fitlake.daily.application.capture.DailyCaptureEditService
import com.fitlake.daily.application.capture.DailyCaptureService
import com.fitlake.daily.domain.audit.DailyCaptureAuditAction
import com.fitlake.daily.domain.capture.DailyCapture
import com.fitlake.daily.domain.capture.DailyCaptureActor
import com.fitlake.daily.domain.capture.DailyCaptureStatus
import com.fitlake.support.ImmediateTransactionExecutor
import com.fitlake.support.InMemoryDailyCaptureAuditRepository
import com.fitlake.support.InMemoryDailyCaptureRepository
import com.fitlake.support.InMemoryDailyDayRepository
import com.fitlake.support.LogEventCapture
import com.fitlake.support.dailyFieldsPayload
import com.fitlake.support.dailyNotePayload
import com.fitlake.support.renderedLogContent
import com.fitlake.support.structuredFields
import com.fitlake.user.domain.UserId
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DailyCaptureServicesTest {
	private val days = InMemoryDailyDayRepository()
	private val captures = InMemoryDailyCaptureRepository()
	private val audits = InMemoryDailyCaptureAuditRepository()
	private val clock = Clock.fixed(Instant.parse("2026-07-28T10:00:00Z"), ZoneId.of("UTC"))
	private val captureService = DailyCaptureService(days, captures, ImmediateTransactionExecutor, clock)
	private val confirmationService = CaptureConfirmationService(
		days,
		captures,
		audits,
		captureService,
		ImmediateTransactionExecutor,
		clock,
	)
	private val editService = DailyCaptureEditService(
		days,
		captures,
		audits,
		captureService,
		ImmediateTransactionExecutor,
		clock,
	)
	private val userId = UserId(UUID.randomUUID())
	private val date = LocalDate.parse("2026-07-28")

	@Test
	fun `v2 user creation opens a day and an open capture`() {
		val capture = captureService.createFromUser(
			userId,
			date,
			dailyFieldsPayload(bodyWeightKg = BigDecimal("78")),
		)

		assertEquals(DailyCaptureStatus.OPEN, capture.status)
		assertEquals(2, capture.payload.schemaVersion)
		assertEquals(1, days.count())
		assertEquals(1, captures.count())
	}

	@Test
	fun `capture can be accepted`() {
		val capture = createCapture()

		lateinit var accepted: DailyCapture
		val logEvents = LogEventCapture(CaptureConfirmationService::class.java).use { logs ->
			accepted = confirmationService.accept(userId, capture.captureId)
			logs.events
		}

		assertEquals(DailyCaptureStatus.ACCEPTED, accepted.status)
		assertTransitionLog(
			event = logEvents.single(),
			eventName = "daily_capture_accepted",
			before = capture,
			after = accepted,
		)
		val audit = audits.all().single()
		assertEquals(DailyCaptureAuditAction.ACCEPT, audit.action)
		assertEquals(DailyCaptureActor.USER_UI, audit.actor)
		assertEquals(DailyCaptureStatus.OPEN, audit.oldStatus)
		assertEquals(DailyCaptureStatus.ACCEPTED, audit.newStatus)
		assertEquals(capture.version, audit.oldVersion)
		assertEquals(accepted.version, audit.newVersion)
	}

	@Test
	fun `rejected capture cannot be accepted`() {
		val capture = createCapture()
		lateinit var rejected: DailyCapture
		val logEvents = LogEventCapture(CaptureConfirmationService::class.java).use { logs ->
			rejected = confirmationService.reject(userId, capture.captureId)
			logs.events
		}
		assertTransitionLog(
			event = logEvents.single(),
			eventName = "daily_capture_rejected",
			before = capture,
			after = rejected,
		)

		assertFailsWith<DailyConflictException> {
			confirmationService.accept(userId, capture.captureId)
		}
		val audit = audits.all().single()
		assertEquals(DailyCaptureAuditAction.REJECT, audit.action)
		assertEquals(DailyCaptureActor.USER_UI, audit.actor)
		assertEquals(DailyCaptureStatus.OPEN, audit.oldStatus)
		assertEquals(DailyCaptureStatus.REJECTED, audit.newStatus)
		assertEquals(capture.version, audit.oldVersion)
		assertEquals(rejected.version, audit.newVersion)
	}

	@Test
	fun `another user cannot access a capture`() {
		val capture = createCapture()

		assertFailsWith<DailyNotFoundException> {
			confirmationService.accept(UserId(UUID.randomUUID()), capture.captureId)
		}
		assertEquals(0, audits.count())
	}

	@Test
	fun `soft delete preserves the capture`() {
		val capture = createCapture()

		lateinit var deleted: DailyCapture
		val logEvents = LogEventCapture(DailyCaptureEditService::class.java).use { logs ->
			deleted = editService.softDelete(userId, capture.captureId)
			logs.events
		}

		assertEquals(DailyCaptureStatus.SOFT_DELETED, deleted.status)
		assertTransitionLog(
			event = logEvents.single(),
			eventName = "daily_capture_soft_deleted",
			before = capture,
			after = deleted,
		)
		assertEquals(1, captures.count())
		val audit = audits.all().single()
		assertEquals(DailyCaptureAuditAction.SOFT_DELETE, audit.action)
		assertEquals(DailyCaptureActor.USER_UI, audit.actor)
		assertEquals(DailyCaptureStatus.OPEN, audit.oldStatus)
		assertEquals(DailyCaptureStatus.SOFT_DELETED, audit.newStatus)
		assertEquals(capture.version, audit.oldVersion)
		assertEquals(deleted.version, audit.newVersion)
	}

	private fun createCapture() = captureService.createFromUser(
		userId,
		date,
		dailyNotePayload(PRIVATE_NOTE),
	)

	private fun assertTransitionLog(
		event: ILoggingEvent,
		eventName: String,
		before: DailyCapture,
		after: DailyCapture,
	) {
		val fields = event.structuredFields()
		assertEquals(eventName, fields["event"])
		assertEquals("success", fields["outcome"])
		assertEquals(userId.value, fields["userRef"])
		assertEquals(before.captureId.value, fields["captureId"])
		assertEquals(before.captureType, fields["captureType"])
		assertEquals(before.status, fields["oldStatus"])
		assertEquals(after.status, fields["newStatus"])
		assertEquals(before.version, fields["oldVersion"])
		assertEquals(after.version, fields["newVersion"])
		assertTrue(fields["durationMs"] is Long)
		assertFalse(fields.containsKey("payload"))
		assertFalse(fields.containsKey("note"))
		assertFalse(listOf(event).renderedLogContent().contains(PRIVATE_NOTE))
	}

	private companion object {
		const val PRIVATE_NOTE = "PRIVATE_CAPTURE_NOTE_7f91"
	}
}
