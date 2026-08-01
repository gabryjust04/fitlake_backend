package com.fitlake.daily.application

import com.fitlake.daily.application.capture.CaptureConfirmationService
import com.fitlake.daily.application.capture.DailyCaptureEditService
import com.fitlake.daily.application.capture.DailyCaptureService
import com.fitlake.daily.domain.capture.DailyCaptureStatus
import com.fitlake.support.ImmediateTransactionExecutor
import com.fitlake.support.InMemoryDailyCaptureRepository
import com.fitlake.support.InMemoryDailyDayRepository
import com.fitlake.support.dailyFieldsPayload
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

class DailyCaptureServicesTest {
	private val days = InMemoryDailyDayRepository()
	private val captures = InMemoryDailyCaptureRepository()
	private val clock = Clock.fixed(Instant.parse("2026-07-28T10:00:00Z"), ZoneId.of("UTC"))
	private val captureService = DailyCaptureService(days, captures, ImmediateTransactionExecutor, clock)
	private val confirmationService = CaptureConfirmationService(
		days,
		captures,
		captureService,
		ImmediateTransactionExecutor,
		clock,
	)
	private val editService = DailyCaptureEditService(
		days,
		captures,
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

		val accepted = confirmationService.accept(userId, capture.captureId)

		assertEquals(DailyCaptureStatus.ACCEPTED, accepted.status)
	}

	@Test
	fun `rejected capture cannot be accepted`() {
		val capture = createCapture()
		confirmationService.reject(userId, capture.captureId)

		assertFailsWith<DailyConflictException> {
			confirmationService.accept(userId, capture.captureId)
		}
	}

	@Test
	fun `another user cannot access a capture`() {
		val capture = createCapture()

		assertFailsWith<DailyNotFoundException> {
			confirmationService.accept(UserId(UUID.randomUUID()), capture.captureId)
		}
	}

	@Test
	fun `soft delete preserves the capture`() {
		val capture = createCapture()

		val deleted = editService.softDelete(userId, capture.captureId)

		assertEquals(DailyCaptureStatus.SOFT_DELETED, deleted.status)
		assertEquals(1, captures.count())
	}

	private fun createCapture() = captureService.createFromUser(
		userId,
		date,
		dailyFieldsPayload(bodyWeightKg = BigDecimal("78")),
	)
}
