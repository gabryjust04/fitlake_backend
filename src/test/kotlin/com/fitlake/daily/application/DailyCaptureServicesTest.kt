package com.fitlake.daily.application

import com.fitlake.daily.application.capture.CaptureConfirmationService
import com.fitlake.daily.application.capture.DailyCaptureEditService
import com.fitlake.daily.application.capture.DailyCaptureInput
import com.fitlake.daily.application.capture.DailyCaptureService
import com.fitlake.daily.application.capture.DailyFieldsInput
import com.fitlake.daily.application.capture.DailyPayloadFactory
import com.fitlake.daily.application.capture.MealInput
import com.fitlake.daily.application.capture.MealItemInput
import com.fitlake.daily.domain.capture.DailyCaptureStatus
import com.fitlake.daily.domain.capture.DailyCaptureType
import com.fitlake.support.ImmediateTransactionExecutor
import com.fitlake.support.InMemoryDailyCaptureRepository
import com.fitlake.support.InMemoryDailyDayRepository
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
	private val payloadFactory = DailyPayloadFactory()
	private val captureService = DailyCaptureService(
		days,
		captures,
		payloadFactory,
		ImmediateTransactionExecutor,
		clock,
	)
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
		payloadFactory,
		ImmediateTransactionExecutor,
		clock,
	)
	private val userId = UserId(UUID.randomUUID())

	@Test
	fun `manual creation opens a day and an open capture`() {
		val capture = captureService.create(userId, LocalDate.parse("2026-07-28"), fieldsInput())

		assertEquals(DailyCaptureStatus.OPEN, capture.status)
		assertEquals(1, days.count())
		assertEquals(1, captures.count())
	}

	@Test
	fun `capture can be accepted and then edited`() {
		val capture = captureService.create(userId, LocalDate.parse("2026-07-28"), fieldsInput())

		val accepted = confirmationService.accept(userId, capture.captureId)
		val edited = editService.replace(
			userId,
			capture.captureId,
			fieldsInput(bodyWeightKg = BigDecimal("78.4")),
		)

		assertEquals(DailyCaptureStatus.ACCEPTED, accepted.status)
		assertEquals(BigDecimal("78.4"), edited.payload.fields.bodyWeightKg)
	}

	@Test
	fun `rejected capture cannot be accepted`() {
		val capture = captureService.create(userId, LocalDate.parse("2026-07-28"), fieldsInput())
		confirmationService.reject(userId, capture.captureId)

		assertFailsWith<DailyConflictException> {
			confirmationService.accept(userId, capture.captureId)
		}
	}

	@Test
	fun `another user cannot access a capture`() {
		val capture = captureService.create(userId, LocalDate.parse("2026-07-28"), fieldsInput())

		assertFailsWith<DailyNotFoundException> {
			confirmationService.accept(UserId(UUID.randomUUID()), capture.captureId)
		}
	}

	@Test
	fun `soft delete preserves the capture`() {
		val capture = captureService.create(userId, LocalDate.parse("2026-07-28"), fieldsInput())

		val deleted = editService.softDelete(userId, capture.captureId)

		assertEquals(DailyCaptureStatus.SOFT_DELETED, deleted.status)
		assertEquals(1, captures.count())
	}

	@Test
	fun `food item edit validates and normalizes quantity and unit`() {
		val capture = captureService.create(
			userId,
			LocalDate.parse("2026-07-28"),
			DailyCaptureInput(
				type = DailyCaptureType.FOOD,
				meals = listOf(
					MealInput(
						mealTempId = "breakfast",
						mealName = "colazione",
						items = listOf(
							MealItemInput(
								itemTempId = "oats",
								foodName = "avena",
								quantity = BigDecimal("40"),
								unit = "g",
								calories = null,
								proteinG = null,
								carbsG = null,
								fatG = null,
							),
						),
					),
				),
			),
		)

		val edited = editService.updateFoodItem(
			userId,
			capture.captureId,
			"oats",
			BigDecimal("50"),
			"grammi",
		)

		assertEquals(BigDecimal("50"), edited.payload.meals.single().items.single().quantity)
		assertEquals("g", edited.payload.meals.single().items.single().unit)
	}

	private fun fieldsInput(bodyWeightKg: BigDecimal = BigDecimal("78")) = DailyCaptureInput(
		type = DailyCaptureType.DAILY_FIELDS,
		fields = DailyFieldsInput(bodyWeightKg = bodyWeightKg),
	)
}
