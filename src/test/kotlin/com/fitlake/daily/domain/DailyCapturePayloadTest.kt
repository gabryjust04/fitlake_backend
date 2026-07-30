package com.fitlake.daily.domain

import com.fitlake.daily.application.DailyValidationException
import com.fitlake.daily.application.capture.DailyCaptureInput
import com.fitlake.daily.application.capture.DailyPayloadFactory
import com.fitlake.daily.application.capture.MealInput
import com.fitlake.daily.application.capture.MealItemInput
import com.fitlake.daily.domain.capture.DailyCaptureType
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class DailyCapturePayloadTest {
	private val factory = DailyPayloadFactory()

	@Test
	fun `food payload normalizes units and generates stable item references`() {
		val payload = factory.create(foodInput(unit = "grammi"))

		assertEquals("g", payload.meals.single().items.single().unit)
		assertTrue(payload.meals.single().mealTempId.startsWith("meal_"))
		assertTrue(payload.meals.single().items.single().itemTempId.startsWith("item_"))
	}

	@Test
	fun `food payload rejects invalid quantity`() {
		assertFailsWith<DailyValidationException> {
			factory.create(foodInput(quantity = BigDecimal.ZERO))
		}
	}

	@Test
	fun `food payload rejects unsupported units`() {
		assertFailsWith<DailyValidationException> {
			factory.create(foodInput(unit = "secchio"))
		}
	}

	@Test
	fun `food type requires at least one meal`() {
		assertFailsWith<DailyValidationException> {
			factory.create(DailyCaptureInput(type = DailyCaptureType.FOOD))
		}
	}

	private fun foodInput(
		quantity: BigDecimal = BigDecimal("40"),
		unit: String = "g",
	) = DailyCaptureInput(
		type = DailyCaptureType.FOOD,
		meals = listOf(
			MealInput(
				mealTempId = null,
				mealName = "colazione",
				items = listOf(
					MealItemInput(
						itemTempId = null,
						foodName = "avena",
						quantity = quantity,
						unit = unit,
						calories = 150,
						proteinG = BigDecimal("5"),
						carbsG = BigDecimal("27"),
						fatG = BigDecimal("3"),
					),
				),
			),
		),
	)
}
