package com.fitlake.daily.application

import com.fitlake.daily.application.finalization.DailyDayReopeningService
import com.fitlake.daily.application.finalization.DailyFinalizationService
import com.fitlake.daily.application.finalization.DailyMetricsProjectionService
import com.fitlake.daily.domain.capture.DailyCapture
import com.fitlake.daily.domain.capture.DailyCaptureEntry
import com.fitlake.daily.domain.capture.DailyCaptureEntryType
import com.fitlake.daily.domain.capture.DailyCapturePayload
import com.fitlake.daily.domain.capture.DailyEnteredQuantity
import com.fitlake.daily.domain.capture.DailyFoodCaptureItem
import com.fitlake.daily.domain.capture.DailyFoodItemSourceType
import com.fitlake.daily.domain.capture.DailyFoodQuantityUnit
import com.fitlake.daily.domain.capture.DailyNutritionValues
import com.fitlake.daily.domain.capture.DailyResolvedFoodUnit
import com.fitlake.daily.domain.capture.DailyResolvedQuantity
import com.fitlake.daily.domain.capture.DailyScalarUnit
import com.fitlake.daily.domain.common.DailyDay
import com.fitlake.daily.domain.common.DailyDayStatus
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
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class DailyFinalizationServiceTest {
	private val days = InMemoryDailyDayRepository()
	private val captures = InMemoryDailyCaptureRepository()
	private val metrics = InMemoryDailyMetricsRepository()
	private val now = Instant.parse("2026-07-28T21:00:00Z")
	private val service = finalizationAt(now)
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
	fun `finalization aggregates nutrition and every personal field from accepted v2 captures`() {
		val day = days.save(DailyDay.open(userId, date, now.minusSeconds(3600)))
		val fields = DailyCapture.openFromUser(userId, day.dayId, fieldsPayload(), now.minusSeconds(3000))
			.accept(now.minusSeconds(2500))
		val food = DailyCapture.openFromUser(userId, day.dayId, foodPayload(), now.minusSeconds(2000))
			.accept(now.minusSeconds(1500))
		val secondFood = DailyCapture.openFromUser(
			userId,
			day.dayId,
			foodPayload(calories = "90", protein = "12", carbs = "4", fat = "2"),
			now.minusSeconds(1800),
		).accept(now.minusSeconds(1400))
		val rejected = DailyCapture.openFromUser(userId, day.dayId, rejectedFieldsPayload(), now.minusSeconds(1000))
			.reject(now.minusSeconds(500))
		captures.save(fields)
		captures.save(food)
		captures.save(secondFood)
		captures.save(rejected)

		val result = service.finalizeDay(userId, date)

		assertEquals(DailyDayStatus.CONFIRMED, result.status)
		assertDecimal("78.4", result.bodyWeightKg)
		assertDecimal("7.5", result.sleepHours)
		assertEquals(12_345, result.stepsCount)
		assertDecimal("2.25", result.hydrationLiters)
		assertEquals(180, result.caffeineMg)
		assertEquals(8, result.moodLevel)
		assertEquals(7, result.focusLevel)
		assertEquals(3, result.stressLevel)
		assertEquals("giornata positiva", result.dailyNotes)
		assertDecimal("315", result.totalCalories)
		assertDecimal("22", result.proteinG)
		assertDecimal("38", result.carbsG)
		assertDecimal("8", result.fatG)
		assertEquals(
			listOf(fields.captureId.value, food.captureId.value, secondFood.captureId.value),
			result.generatedFromCaptureIds,
		)
		assertEquals(2, result.foodLog.size)
		assertEquals(3, result.foodLog.sumOf { it.items.size })
	}

	@Test
	fun `an unknown nutrient in one accepted food keeps only that daily total unknown`() {
		val day = days.save(DailyDay.open(userId, date, now.minusSeconds(3600)))
		captures.save(
			DailyCapture.openFromUser(
				userId,
				day.dayId,
				foodPayload(calories = "100", protein = "10", carbs = "20", fat = "5"),
				now.minusSeconds(2000),
			).accept(now.minusSeconds(1500)),
		)
		captures.save(
			DailyCapture.openFromUser(
				userId,
				day.dayId,
				foodPayloadWithUnknownProtein(),
				now.minusSeconds(1000),
			).accept(now.minusSeconds(500)),
		)

		val result = service.finalizeDay(userId, date)

		assertDecimal("150", result.totalCalories)
		assertNull(result.proteinG)
		assertDecimal("30", result.carbsG)
		assertDecimal("7", result.fatG)
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
	fun `finalization is idempotent while confirmed`() {
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
				weightPayload("78.4"),
				now.minusSeconds(3000),
			).accept(now.minusSeconds(2500)),
		)
		captures.save(
			DailyCapture.openFromUser(
				userId,
				day.dayId,
				weightPayload("79.1"),
				now.minusSeconds(2000),
			).accept(now.minusSeconds(1500)),
		)

		val result = service.finalizeDay(userId, date)

		assertDecimal("79.1", result.bodyWeightKg)
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

	@Test
	fun `reopened finalization replaces the old snapshot from accepted captures and remains idempotent`() {
		val day = days.save(DailyDay.open(userId, date, now.minusSeconds(3600)))
		val originalFields = captures.save(
			DailyCapture.openFromUser(userId, day.dayId, fieldsPayload(), now.minusSeconds(3000))
				.accept(now.minusSeconds(2500)),
		)
		val originalFood = captures.save(
			DailyCapture.openFromUser(userId, day.dayId, foodPayload(), now.minusSeconds(2000))
				.accept(now.minusSeconds(1500)),
		)
		val first = service.finalizeDay(userId, date)
		val reopenedAt = now.plusSeconds(60)

		val reopened = reopeningAt(reopenedAt).reopenDay(userId, date)

		assertEquals(DailyDayStatus.REOPENED, reopened.status)
		assertEquals(reopenedAt, reopened.reopenedAt)
		assertEquals(DailyDayStatus.REOPENED, metrics.findByDayId(day.dayId)?.status)

		captures.save(originalFields.replacePayload(weightPayload("80.2"), now.plusSeconds(70)))
		captures.save(originalFood.softDelete(now.plusSeconds(75)))
		val replacementFood = captures.save(
			DailyCapture.openFromUser(
				userId,
				day.dayId,
				foodPayload(calories = "90", protein = "12", carbs = "4", fat = "2"),
				now.plusSeconds(80),
			).accept(now.plusSeconds(85)),
		)

		val refinalizedAt = now.plusSeconds(120)
		val refinalizationService = finalizationAt(refinalizedAt)
		val second = refinalizationService.finalizeDay(userId, date)

		assertEquals(DailyDayStatus.CONFIRMED, second.status)
		assertEquals(DailyDayStatus.CONFIRMED, days.findById(day.dayId)?.status)
		assertDecimal("80.2", second.bodyWeightKg)
		assertNull(second.sleepHours)
		assertNull(second.stepsCount)
		assertNull(second.hydrationLiters)
		assertNull(second.caffeineMg)
		assertNull(second.moodLevel)
		assertNull(second.focusLevel)
		assertNull(second.stressLevel)
		assertNull(second.dailyNotes)
		assertDecimal("90", second.totalCalories)
		assertDecimal("12", second.proteinG)
		assertDecimal("4", second.carbsG)
		assertDecimal("2", second.fatG)
		assertEquals(listOf(originalFields.captureId.value, replacementFood.captureId.value), second.generatedFromCaptureIds)
		assertEquals(first.createdAt, second.createdAt)
		assertEquals(refinalizedAt, second.confirmedAt)
		assertEquals(refinalizedAt, second.recalculatedAt)
		assertEquals(3, metrics.saveCount)

		assertEquals(second, refinalizationService.finalizeDay(userId, date))
		assertEquals(3, metrics.saveCount)
	}

	@Test
	fun `an open capture still blocks finalization after reopening`() {
		val day = days.save(DailyDay.open(userId, date, now.minusSeconds(3600)))
		service.finalizeDay(userId, date)
		reopeningAt(now.plusSeconds(60)).reopenDay(userId, date)
		captures.save(DailyCapture.openFromUser(userId, day.dayId, weightPayload("81"), now.plusSeconds(70)))

		assertFailsWith<DailyConflictException> {
			finalizationAt(now.plusSeconds(120)).finalizeDay(userId, date)
		}
		assertEquals(DailyDayStatus.REOPENED, days.findById(day.dayId)?.status)
		assertEquals(DailyDayStatus.REOPENED, metrics.findByDayId(day.dayId)?.status)
	}

	@Test
	fun `reopening is ownership safe and rejects a day that was never finalized`() {
		days.save(DailyDay.open(userId, date, now.minusSeconds(3600)))
		val reopening = reopeningAt(now.plusSeconds(60))

		assertFailsWith<DailyNotFoundException> {
			reopening.reopenDay(UserId(UUID.randomUUID()), date)
		}
		assertFailsWith<DailyConflictException> {
			reopening.reopenDay(userId, date)
		}
	}

	@Test
	fun `a reopened day requires both confirmation and reopening timestamps`() {
		val open = DailyDay.open(userId, date, now.minusSeconds(3600))

		assertFailsWith<IllegalArgumentException> {
			open.copy(status = DailyDayStatus.REOPENED)
		}
		assertFailsWith<IllegalArgumentException> {
			open.confirm(now.minusSeconds(60)).copy(status = DailyDayStatus.REOPENED, reopenedAt = null)
		}
	}

	@Test
	fun `reopening requires the confirmed metrics snapshot`() {
		val confirmed = DailyDay.open(userId, date, now.minusSeconds(3600)).confirm(now.minusSeconds(60))
		days.save(confirmed)

		assertFailsWith<DailyStateCorruptionException> {
			reopeningAt(now).reopenDay(userId, date)
		}
		assertEquals(DailyDayStatus.CONFIRMED, days.findById(confirmed.dayId)?.status)
	}

	@Test
	fun `reopening is idempotent only while the reopened snapshot remains consistent`() {
		val day = days.save(DailyDay.open(userId, date, now.minusSeconds(3600)))
		service.finalizeDay(userId, date)
		val reopening = reopeningAt(now.plusSeconds(60))

		val first = reopening.reopenDay(userId, date)
		val second = reopening.reopenDay(userId, date)

		assertEquals(first, second)
		assertEquals(2, metrics.saveCount)

		val stale = requireNotNull(metrics.findByDayId(day.dayId)).copy(status = DailyDayStatus.CONFIRMED)
		metrics.save(stale)
		assertFailsWith<DailyStateCorruptionException> {
			reopening.reopenDay(userId, date)
		}
	}

	private fun finalizationAt(at: Instant) = DailyFinalizationService(
		days,
		captures,
		metrics,
		DailyMetricsProjectionService(),
		ImmediateTransactionExecutor,
		Clock.fixed(at, ZoneOffset.UTC),
	)

	private fun reopeningAt(at: Instant) = DailyDayReopeningService(
		days,
		metrics,
		ImmediateTransactionExecutor,
		Clock.fixed(at, ZoneOffset.UTC),
	)

	private fun fieldsPayload(): DailyCapturePayload = DailyCapturePayload.fromEntries(
		listOf(
			scalarEntry(DailyCaptureEntryType.WEIGHT, "78.4", DailyScalarUnit.KILOGRAM),
			scalarEntry(DailyCaptureEntryType.SLEEP, "7.5", DailyScalarUnit.HOUR),
			scalarEntry(DailyCaptureEntryType.STEPS, "12345", DailyScalarUnit.COUNT),
			scalarEntry(DailyCaptureEntryType.HYDRATION, "2.25", DailyScalarUnit.LITER),
			scalarEntry(DailyCaptureEntryType.CAFFEINE, "180", DailyScalarUnit.MILLIGRAM),
			scalarEntry(DailyCaptureEntryType.MOOD, "8", DailyScalarUnit.LEVEL),
			scalarEntry(DailyCaptureEntryType.FOCUS, "7", DailyScalarUnit.LEVEL),
			scalarEntry(DailyCaptureEntryType.STRESS, "3", DailyScalarUnit.LEVEL),
			DailyCaptureEntry(
				entryId = UUID.randomUUID(),
				type = DailyCaptureEntryType.DAILY_NOTES,
				text = "giornata positiva",
			),
		),
	)

	private fun weightPayload(value: String): DailyCapturePayload = DailyCapturePayload.fromEntries(
		listOf(scalarEntry(DailyCaptureEntryType.WEIGHT, value, DailyScalarUnit.KILOGRAM)),
	)

	private fun rejectedFieldsPayload(): DailyCapturePayload = weightPayload("100")

	private fun foodPayload(
		calories: String = "225",
		protein: String = "10",
		carbs: String = "34",
		fat: String = "6",
	): DailyCapturePayload {
		val items = if (calories == "225" && protein == "10" && carbs == "34" && fat == "6") {
			listOf(
				foodItem("avena", "40", DailyResolvedFoodUnit.GRAM, "150", "5", "27", "3"),
				foodItem("latte", "150", DailyResolvedFoodUnit.MILLILITER, "75", "5", "7", "3"),
			)
		} else {
			listOf(foodItem("pollo", "100", DailyResolvedFoodUnit.GRAM, calories, protein, carbs, fat))
		}
		val nutritionTotal = DailyNutritionValues.strictTotal(items.map(DailyFoodCaptureItem::calculatedNutrition))
		return DailyCapturePayload.fromEntries(
			listOf(
				DailyCaptureEntry(
					entryId = UUID.randomUUID(),
					type = DailyCaptureEntryType.FOOD,
					items = items,
					nutritionTotal = nutritionTotal,
				),
			),
		)
	}

	private fun foodPayloadWithUnknownProtein(): DailyCapturePayload {
		val items = listOf(
			foodItem(
				name = "alimento senza proteine note",
				quantity = "100",
				resolvedUnit = DailyResolvedFoodUnit.GRAM,
				calories = "50",
				protein = null,
				carbs = "10",
				fat = "2",
			),
		)
		return DailyCapturePayload.fromEntries(
			listOf(
				DailyCaptureEntry(
					entryId = UUID.randomUUID(),
					type = DailyCaptureEntryType.FOOD,
					items = items,
					nutritionTotal = DailyNutritionValues.strictTotal(items.map(DailyFoodCaptureItem::calculatedNutrition)),
				),
			),
		)
	}

	private fun foodItem(
		name: String,
		quantity: String,
		resolvedUnit: DailyResolvedFoodUnit,
		calories: String,
		protein: String?,
		carbs: String,
		fat: String,
	): DailyFoodCaptureItem {
		val enteredUnit = when (resolvedUnit) {
			DailyResolvedFoodUnit.GRAM -> DailyFoodQuantityUnit.GRAM
			DailyResolvedFoodUnit.MILLILITER -> DailyFoodQuantityUnit.MILLILITER
			DailyResolvedFoodUnit.PIECE -> DailyFoodQuantityUnit.PIECE
			DailyResolvedFoodUnit.SERVING -> DailyFoodQuantityUnit.SERVING
		}
		return DailyFoodCaptureItem(
			itemId = UUID.randomUUID(),
			sourceType = DailyFoodItemSourceType.MANUAL_NUTRITION,
			userFoodId = null,
			displayName = name,
			brand = null,
			enteredQuantity = DailyEnteredQuantity(BigDecimal(quantity), enteredUnit),
			resolvedQuantity = DailyResolvedQuantity(BigDecimal(quantity), resolvedUnit),
			userFoodSnapshot = null,
			calculatedNutrition = DailyNutritionValues(
				caloriesKcal = BigDecimal(calories),
				proteinGrams = protein?.let { BigDecimal(it) },
				carbohydratesGrams = BigDecimal(carbs),
				fatGrams = BigDecimal(fat),
			),
		)
	}

	private fun scalarEntry(
		type: DailyCaptureEntryType,
		value: String,
		unit: DailyScalarUnit,
	) = DailyCaptureEntry(
		entryId = UUID.randomUUID(),
		type = type,
		value = BigDecimal(value),
		unit = unit,
	)

	private fun assertDecimal(expected: String, actual: BigDecimal?) {
		val value = requireNotNull(actual)
		assertEquals(0, BigDecimal(expected).compareTo(value))
	}
}
