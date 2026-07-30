package com.fitlake.daily.application

import com.fitlake.daily.application.capture.DailyPayloadFactory
import com.fitlake.daily.application.capture.DailyCaptureInput
import com.fitlake.daily.application.capture.DailyFieldsInput
import com.fitlake.daily.application.capture.MealInput
import com.fitlake.daily.application.capture.MealItemInput
import com.fitlake.daily.application.finalization.DailyFinalizationService
import com.fitlake.daily.application.finalization.DailyMetricsProjectionService
import com.fitlake.daily.domain.capture.DailyCapture
import com.fitlake.daily.domain.capture.DailyCaptureType
import com.fitlake.daily.domain.common.DailyDay
import com.fitlake.support.ImmediateTransactionExecutor
import com.fitlake.support.InMemoryDailyCaptureRepository
import com.fitlake.support.InMemoryDailyDayRepository
import com.fitlake.support.InMemoryDailyMetricsRepository
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
import kotlin.test.assertNull

class DailyFinalizationServiceTest {
	private val days = InMemoryDailyDayRepository()
	private val captures = InMemoryDailyCaptureRepository()
	private val metrics = InMemoryDailyMetricsRepository()
	private val now = Instant.parse("2026-07-28T21:00:00Z")
	private val clock = Clock.fixed(now, ZoneId.of("UTC"))
	private val service = DailyFinalizationService(
		days,
		captures,
		metrics,
		DailyMetricsProjectionService(),
		ImmediateTransactionExecutor,
		clock,
	)
	private val factory = DailyPayloadFactory()
	private val userId = UserId(UUID.randomUUID())
	private val date = LocalDate.parse("2026-07-28")

	@Test
	fun `open captures block finalization`() {
		val day = days.save(DailyDay.open(userId, date, now.minusSeconds(3600)))
		captures.save(DailyCapture.openFromUser(userId, day.dayId, fieldsPayload(), now.minusSeconds(3000)))

		assertFailsWith<DailyConflictException> {
			service.finalizeDay(userId, date)
		}
	}

	@Test
	fun `finalization aggregates accepted captures and excludes rejected captures`() {
		val day = days.save(DailyDay.open(userId, date, now.minusSeconds(3600)))
		val fields = DailyCapture.openFromUser(userId, day.dayId, fieldsPayload(), now.minusSeconds(3000))
			.accept(now.minusSeconds(2500))
		val food = DailyCapture.openFromUser(userId, day.dayId, foodPayload(), now.minusSeconds(2000))
			.accept(now.minusSeconds(1500))
		val rejected = DailyCapture.openFromUser(userId, day.dayId, rejectedFieldsPayload(), now.minusSeconds(1000))
			.reject(now.minusSeconds(500))
		captures.save(fields)
		captures.save(food)
		captures.save(rejected)

		val result = service.finalizeDay(userId, date)

		assertEquals(BigDecimal("78.4"), result.bodyWeightKg)
		assertEquals(225, result.totalCalories)
		assertEquals(BigDecimal("10"), result.proteinG)
		assertEquals(2, result.generatedFromCaptureIds.size)
		assertEquals(1, result.foodLog.size)
	}

	@Test
	fun `rejected values do not leak into metrics`() {
		val day = days.save(DailyDay.open(userId, date, now.minusSeconds(3600)))
		captures.save(
			DailyCapture.openFromUser(userId, day.dayId, rejectedFieldsPayload(), now.minusSeconds(1000))
				.reject(now.minusSeconds(500)),
		)

		val result = service.finalizeDay(userId, date)

		assertNull(result.bodyWeightKg)
	}

	@Test
	fun `finalization is idempotent`() {
		days.save(DailyDay.open(userId, date, now.minusSeconds(3600)))

		val first = service.finalizeDay(userId, date)
		val second = service.finalizeDay(userId, date)

		assertEquals(first, second)
		assertEquals(1, metrics.saveCount)
	}

	@Test
	fun `latest accepted scalar value wins deterministically`() {
		val day = days.save(DailyDay.open(userId, date, now.minusSeconds(3600)))
		captures.save(
			DailyCapture.openFromUser(
				userId,
				day.dayId,
				fieldsPayload(BigDecimal("78.4")),
				now.minusSeconds(3000),
			).accept(now.minusSeconds(2500)),
		)
		captures.save(
			DailyCapture.openFromUser(
				userId,
				day.dayId,
				fieldsPayload(BigDecimal("79.1")),
				now.minusSeconds(2000),
			).accept(now.minusSeconds(1500)),
		)

		val result = service.finalizeDay(userId, date)

		assertEquals(BigDecimal("79.1"), result.bodyWeightKg)
	}

	@Test
	fun `soft deleted accepted capture is excluded from metrics`() {
		val day = days.save(DailyDay.open(userId, date, now.minusSeconds(3600)))
		val deleted = DailyCapture.openFromUser(userId, day.dayId, foodPayload(), now.minusSeconds(2000))
			.accept(now.minusSeconds(1500))
			.softDelete(now.minusSeconds(1000))
		captures.save(deleted)

		val result = service.finalizeDay(userId, date)

		assertNull(result.totalCalories)
		assertEquals(emptyList(), result.generatedFromCaptureIds)
	}

	private fun fieldsPayload(bodyWeightKg: BigDecimal = BigDecimal("78.4")) = factory.create(
		DailyCaptureInput(
			type = DailyCaptureType.DAILY_FIELDS,
			fields = DailyFieldsInput(bodyWeightKg = bodyWeightKg, sleepHours = BigDecimal("7.5")),
		),
	)

	private fun rejectedFieldsPayload() = factory.create(
		DailyCaptureInput(
			type = DailyCaptureType.DAILY_FIELDS,
			fields = DailyFieldsInput(bodyWeightKg = BigDecimal("100")),
		),
	)

	private fun foodPayload() = factory.create(
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
							calories = 150,
							proteinG = BigDecimal("5"),
							carbsG = BigDecimal("27"),
							fatG = BigDecimal("3"),
						),
						MealItemInput(
							itemTempId = "milk",
							foodName = "latte",
							quantity = BigDecimal("150"),
							unit = "ml",
							calories = 75,
							proteinG = BigDecimal("5"),
							carbsG = BigDecimal("7"),
							fatG = BigDecimal("3"),
						),
					),
				),
			),
		),
	)
}
