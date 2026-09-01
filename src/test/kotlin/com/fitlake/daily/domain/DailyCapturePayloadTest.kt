package com.fitlake.daily.domain

import com.fitlake.daily.domain.capture.DAILY_CAPTURE_SCHEMA_VERSION
import com.fitlake.daily.domain.capture.DailyCaptureEntry
import com.fitlake.daily.domain.capture.DailyCaptureEntryType
import com.fitlake.daily.domain.capture.DailyCapturePayload
import com.fitlake.daily.domain.capture.DailyCaptureType
import com.fitlake.daily.domain.capture.DailyFoodQuantityUnit
import com.fitlake.daily.domain.capture.DailyScalarUnit
import com.fitlake.daily.domain.capture.MealItemDraft
import com.fitlake.support.dailyFoodPayload
import com.fitlake.support.manualNutritionItem
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class DailyCapturePayloadTest {
	@Test
	fun `typed entries create the current v2 payload and derived projection`() {
		val payload = DailyCapturePayload.fromEntries(
			listOf(
				DailyCaptureEntry(
					entryId = UUID.randomUUID(),
					type = DailyCaptureEntryType.WEIGHT,
					value = BigDecimal("78.4"),
					unit = DailyScalarUnit.KILOGRAM,
				),
			),
		)

		assertEquals(DAILY_CAPTURE_SCHEMA_VERSION, payload.schemaVersion)
		assertEquals(DailyCaptureType.DAILY_FIELDS, payload.type)
		assertEquals(BigDecimal("78.4"), payload.fields.bodyWeightKg)
	}

	@Test
	fun `schema v1 cannot be instantiated`() {
		val entry = DailyCaptureEntry(
			entryId = UUID.randomUUID(),
			type = DailyCaptureEntryType.NOTE,
			text = "nota",
		)

		assertFailsWith<IllegalArgumentException> {
			DailyCapturePayload(
				type = DailyCaptureType.NOTE,
				note = "nota",
				schemaVersion = 1,
				entries = listOf(entry),
			)
		}
	}

	@Test
	fun `empty typed payload is rejected`() {
		assertFailsWith<IllegalArgumentException> { DailyCapturePayload.fromEntries(emptyList()) }
	}

	@Test
	fun `v2 numeric maximum survives the legacy meal projection`() {
		val maximum = BigDecimal("1000000000000")
		val payload = dailyFoodPayload(
			listOf(
				manualNutritionItem(
					foodName = "Boundary food",
					quantity = maximum,
					unit = DailyFoodQuantityUnit.GRAM,
					calories = maximum,
					protein = maximum,
					carbohydrates = maximum,
					fat = maximum,
				),
			),
		)

		val projected = payload.meals.single().items.single()
		assertEquals(maximum, projected.quantity)
		assertEquals(maximum, projected.calories)
		assertEquals(maximum, projected.proteinG)
		assertEquals(maximum, projected.carbsG)
		assertEquals(maximum, projected.fatG)
	}

	@Test
	fun `legacy meal projection matches v2 magnitude and scale boundaries`() {
		val supportedScaleBoundary = BigDecimal("0.000001")
		assertEquals(
			supportedScaleBoundary,
			mealItem(quantity = supportedScaleBoundary, calories = supportedScaleBoundary).calories,
		)
		assertFailsWith<IllegalArgumentException> {
			mealItem(quantity = BigDecimal("1000000000000.000001"))
		}
		assertFailsWith<IllegalArgumentException> {
			mealItem(quantity = BigDecimal.ONE, calories = BigDecimal("0.0000001"))
		}
	}

	private fun mealItem(
		quantity: BigDecimal,
		calories: BigDecimal = BigDecimal.ZERO,
	) = MealItemDraft(
		itemTempId = "item",
		foodName = "Boundary food",
		quantity = quantity,
		unit = "g",
		calories = calories,
		proteinG = BigDecimal.ZERO,
		carbsG = BigDecimal.ZERO,
		fatG = BigDecimal.ZERO,
	)
}
