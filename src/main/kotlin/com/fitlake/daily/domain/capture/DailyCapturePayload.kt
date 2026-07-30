package com.fitlake.daily.domain.capture

import java.math.BigDecimal
import java.util.Locale

enum class DailyCaptureType {
	FOOD,
	DAILY_FIELDS,
	MIXED,
	NOTE,
}

data class MealItemDraft(
	val itemTempId: String,
	val foodName: String,
	val quantity: BigDecimal,
	val unit: String,
	val calories: Int? = null,
	val proteinG: BigDecimal? = null,
	val carbsG: BigDecimal? = null,
	val fatG: BigDecimal? = null,
) {
	init {
		require(itemTempId.isNotBlank()) { "Food item reference must not be blank" }
		require(itemTempId.length <= 100) { "Food item reference must not exceed 100 characters" }
		require(foodName.isNotBlank()) { "Food name must not be blank" }
		require(foodName.length <= 255) { "Food name must not exceed 255 characters" }
		require(quantity > BigDecimal.ZERO) { "Food quantity must be greater than zero" }
		require(quantity <= BigDecimal("1000000")) { "Food quantity is outside the allowed range" }
		require(unit in ALLOWED_UNITS) { "Unsupported food unit: $unit" }
		require(calories == null || calories in 0..100_000) { "Calories are outside the allowed range" }
		require(proteinG.isNullOrInRange(MAX_MACRO_GRAMS)) { "Protein is outside the allowed range" }
		require(carbsG.isNullOrInRange(MAX_MACRO_GRAMS)) { "Carbohydrates are outside the allowed range" }
		require(fatG.isNullOrInRange(MAX_MACRO_GRAMS)) { "Fat is outside the allowed range" }
	}

	companion object {
		val ALLOWED_UNITS = setOf("g", "kg", "ml", "l", "unit", "portion")
		private val MAX_MACRO_GRAMS = BigDecimal("5000")

		private fun BigDecimal?.isNullOrInRange(max: BigDecimal): Boolean =
			this == null || (this >= BigDecimal.ZERO && this <= max)
	}
}

data class MealDraft(
	val mealTempId: String,
	val mealName: String?,
	val items: List<MealItemDraft>,
) {
	init {
		require(mealTempId.isNotBlank()) { "Meal reference must not be blank" }
		require(mealTempId.length <= 100) { "Meal reference must not exceed 100 characters" }
		require(mealName == null || mealName.isNotBlank()) { "Meal name must be null or non-blank" }
		require(mealName == null || mealName.length <= 100) { "Meal name must not exceed 100 characters" }
		require(items.isNotEmpty()) { "A meal requires at least one food item" }
		require(items.size <= 200) { "A meal cannot contain more than 200 food items" }
		require(items.map { it.itemTempId }.distinct().size == items.size) {
			"Food item references must be unique within a meal"
		}
	}
}

data class DailyFields(
	val bodyWeightKg: BigDecimal? = null,
	val sleepHours: BigDecimal? = null,
	val stepsCount: Int? = null,
	val hydrationLiters: BigDecimal? = null,
	val caffeineMg: Int? = null,
	val moodLevel: Int? = null,
	val focusLevel: Int? = null,
	val stressLevel: Int? = null,
	val dailyNotes: String? = null,
) {
	init {
		require(bodyWeightKg.isNullOrBetween("1", "500")) { "Body weight must be between 1 and 500 kg" }
		require(sleepHours.isNullOrBetween("0", "24")) { "Sleep hours must be between 0 and 24" }
		require(stepsCount == null || stepsCount in 0..200_000) { "Steps are outside the allowed range" }
		require(hydrationLiters.isNullOrBetween("0", "20")) { "Hydration must be between 0 and 20 liters" }
		require(caffeineMg == null || caffeineMg in 0..5_000) { "Caffeine is outside the allowed range" }
		require(moodLevel == null || moodLevel in 1..10) { "Mood level must be between 1 and 10" }
		require(focusLevel == null || focusLevel in 1..10) { "Focus level must be between 1 and 10" }
		require(stressLevel == null || stressLevel in 1..10) { "Stress level must be between 1 and 10" }
		require(dailyNotes == null || dailyNotes.isNotBlank()) { "Daily notes must be null or non-blank" }
		require(dailyNotes == null || dailyNotes.length <= 10_000) { "Daily notes are too long" }
	}

	fun hasValues(): Boolean = listOf(
		bodyWeightKg,
		sleepHours,
		stepsCount,
		hydrationLiters,
		caffeineMg,
		moodLevel,
		focusLevel,
		stressLevel,
		dailyNotes,
	).any { it != null }

	private fun BigDecimal?.isNullOrBetween(min: String, max: String): Boolean =
		this == null || (this >= min.toBigDecimal() && this <= max.toBigDecimal())
}

data class DailyCapturePayload(
	val type: DailyCaptureType,
	val meals: List<MealDraft> = emptyList(),
	val fields: DailyFields = DailyFields(),
	val note: String? = null,
) {
	init {
		require(meals.size <= 50) { "A capture cannot contain more than 50 meals" }
		require(meals.map { it.mealTempId }.distinct().size == meals.size) {
			"Meal references must be unique within a capture"
		}
		val itemReferences = meals.flatMap { meal -> meal.items.map(MealItemDraft::itemTempId) }
		require(itemReferences.distinct().size == itemReferences.size) {
			"Food item references must be unique within a capture"
		}
		require(note == null || note.isNotBlank()) { "Note must be null or non-blank" }
		require(note == null || note.length <= 10_000) { "Note is too long" }

		when (type) {
			DailyCaptureType.FOOD -> require(meals.isNotEmpty() && !fields.hasValues() && note == null) {
				"FOOD payload requires meals only"
			}
			DailyCaptureType.DAILY_FIELDS -> require(meals.isEmpty() && fields.hasValues() && note == null) {
				"DAILY_FIELDS payload requires at least one daily field"
			}
			DailyCaptureType.MIXED -> require(meals.isNotEmpty() && fields.hasValues() && note == null) {
				"MIXED payload requires both meals and daily fields"
			}
			DailyCaptureType.NOTE -> require(meals.isEmpty() && !fields.hasValues() && note != null) {
				"NOTE payload requires note text only"
			}
		}
	}

	fun updateFoodItem(itemTempId: String, quantity: BigDecimal, unit: String): DailyCapturePayload {
		var matches = 0
		val updatedMeals = meals.map { meal ->
			meal.copy(
				items = meal.items.map { item ->
					if (item.itemTempId == itemTempId) {
						matches += 1
						item.copy(quantity = quantity, unit = unit)
					} else {
						item
					}
				},
			)
		}
		require(matches == 1) { "Food item reference was not found or is ambiguous" }
		return copy(meals = updatedMeals)
	}
}

object FoodUnitNormalizer {
	private val aliases = mapOf(
		"g" to "g",
		"gr" to "g",
		"grammo" to "g",
		"grammi" to "g",
		"kg" to "kg",
		"chilogrammo" to "kg",
		"chilogrammi" to "kg",
		"ml" to "ml",
		"millilitro" to "ml",
		"millilitri" to "ml",
		"l" to "l",
		"litro" to "l",
		"litri" to "l",
		"unit" to "unit",
		"unita" to "unit",
		"unità" to "unit",
		"pezzo" to "unit",
		"pezzi" to "unit",
		"portion" to "portion",
		"porzione" to "portion",
		"porzioni" to "portion",
	)

	fun normalize(unit: String): String = aliases[unit.trim().lowercase(Locale.ROOT)]
		?: throw IllegalArgumentException("Unsupported food unit: $unit")
}
