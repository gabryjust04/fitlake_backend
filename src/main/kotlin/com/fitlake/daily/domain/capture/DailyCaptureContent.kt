package com.fitlake.daily.domain.capture

import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.Locale
import java.util.UUID

const val DAILY_CAPTURE_SCHEMA_VERSION = 2

enum class DailyCaptureEntryType {
	FOOD,
	WEIGHT,
	SLEEP,
	STEPS,
	HYDRATION,
	CAFFEINE,
	MOOD,
	FOCUS,
	STRESS,
	DAILY_NOTES,
	NOTE,
}

enum class DailyMealType {
	BREAKFAST,
	MORNING_SNACK,
	LUNCH,
	AFTERNOON_SNACK,
	DINNER,
	EVENING_SNACK,
	OTHER,
}

enum class DailyFoodItemSourceType {
	USER_FOOD,
	MANUAL_NUTRITION,
	AI_ESTIMATE,
}

enum class DailyFoodQuantityUnit {
	GRAM,
	KILOGRAM,
	MILLILITER,
	LITER,
	PIECE,
	SERVING,
	DEFAULT_SERVING,
}

enum class DailyFoodSnapshotUnit {
	GRAM,
	KILOGRAM,
	MILLILITER,
	LITER,
	PIECE,
	SERVING,
}

enum class DailyResolvedFoodUnit {
	GRAM,
	MILLILITER,
	PIECE,
	SERVING,
}

enum class DailyScalarUnit {
	GRAM,
	KILOGRAM,
	MILLILITER,
	LITER,
	HOUR,
	COUNT,
	MILLIGRAM,
	LEVEL,
}

data class DailyEnteredQuantity(
	val amount: BigDecimal,
	val unit: DailyFoodQuantityUnit,
) {
	init {
		requireDailyPositiveDecimal(amount, "Consumed quantity")
	}

	fun numericallyEquals(other: DailyEnteredQuantity): Boolean =
		unit == other.unit && amount.compareTo(other.amount) == 0
}

data class DailyResolvedQuantity(
	val amount: BigDecimal,
	val unit: DailyResolvedFoodUnit,
) {
	init {
		requireDailyPositiveDecimal(amount, "Resolved quantity")
	}
}

data class DailyFoodBasisSnapshot(
	val amount: BigDecimal,
	val unit: DailyFoodSnapshotUnit,
) {
	init {
		requireDailyPositiveDecimal(amount, "Nutrition basis amount")
	}
}

data class DailyFoodDefaultServingSnapshot(
	val amount: BigDecimal,
	val unit: DailyFoodSnapshotUnit,
) {
	init {
		requireDailyPositiveDecimal(amount, "Default serving amount")
	}
}

data class DailyFoodConversionSnapshot(
	val gramsPerPiece: BigDecimal? = null,
	val millilitersPerPiece: BigDecimal? = null,
	val gramsPerServing: BigDecimal? = null,
	val millilitersPerServing: BigDecimal? = null,
) {
	init {
		listOf(
			"Grams per piece" to gramsPerPiece,
			"Milliliters per piece" to millilitersPerPiece,
			"Grams per serving" to gramsPerServing,
			"Milliliters per serving" to millilitersPerServing,
		).forEach { (name, value) -> value?.let { requireDailyPositiveDecimal(it, name) } }
		require(gramsPerPiece == null || millilitersPerPiece == null) {
			"A piece cannot have both mass and volume conversions"
		}
		require(gramsPerServing == null || millilitersPerServing == null) {
			"A serving cannot have both mass and volume conversions"
		}
	}
}

data class DailyNutritionValues(
	val caloriesKcal: BigDecimal? = null,
	val proteinGrams: BigDecimal? = null,
	val carbohydratesGrams: BigDecimal? = null,
	val fatGrams: BigDecimal? = null,
	val fiberGrams: BigDecimal? = null,
	val sugarsGrams: BigDecimal? = null,
	val saturatedFatGrams: BigDecimal? = null,
	val sodiumMilligrams: BigDecimal? = null,
	val saltGrams: BigDecimal? = null,
) {
	init {
		listOf(
			"Calories" to caloriesKcal,
			"Protein" to proteinGrams,
			"Carbohydrates" to carbohydratesGrams,
			"Fat" to fatGrams,
			"Fiber" to fiberGrams,
			"Sugars" to sugarsGrams,
			"Saturated fat" to saturatedFatGrams,
			"Sodium" to sodiumMilligrams,
			"Salt" to saltGrams,
		).forEach { (name, value) -> value?.let { requireDailyNonNegativeDecimal(it, name) } }
	}

	fun numericallyEquals(other: DailyNutritionValues): Boolean = listOf(
		caloriesKcal to other.caloriesKcal,
		proteinGrams to other.proteinGrams,
		carbohydratesGrams to other.carbohydratesGrams,
		fatGrams to other.fatGrams,
		fiberGrams to other.fiberGrams,
		sugarsGrams to other.sugarsGrams,
		saturatedFatGrams to other.saturatedFatGrams,
		sodiumMilligrams to other.sodiumMilligrams,
		saltGrams to other.saltGrams,
	).all { (left, right) -> left == null && right == null || left != null && right != null && left.compareTo(right) == 0 }

	companion object {
		fun strictTotal(values: List<DailyNutritionValues>): DailyNutritionValues {
			require(values.isNotEmpty()) { "Nutrition total requires at least one item" }
			fun total(selector: (DailyNutritionValues) -> BigDecimal?): BigDecimal? {
				val selected = values.map(selector)
				if (selected.any { it == null }) return null
				return selected.filterNotNull().fold(BigDecimal.ZERO, BigDecimal::add).normalizedDailyDecimal()
			}
			return DailyNutritionValues(
				caloriesKcal = total(DailyNutritionValues::caloriesKcal),
				proteinGrams = total(DailyNutritionValues::proteinGrams),
				carbohydratesGrams = total(DailyNutritionValues::carbohydratesGrams),
				fatGrams = total(DailyNutritionValues::fatGrams),
				fiberGrams = total(DailyNutritionValues::fiberGrams),
				sugarsGrams = total(DailyNutritionValues::sugarsGrams),
				saturatedFatGrams = total(DailyNutritionValues::saturatedFatGrams),
				sodiumMilligrams = total(DailyNutritionValues::sodiumMilligrams),
				saltGrams = total(DailyNutritionValues::saltGrams),
			)
		}
	}
}

data class DailyNutritionSourceSnapshot(
	val type: DailyFoodItemSourceType,
	val originalSourceType: String,
	val estimated: Boolean,
	val provider: String? = null,
	val externalId: String? = null,
	val notes: String? = null,
	val copiedAt: LocalDate? = null,
) {
	init {
		require(originalSourceType.isNotBlank()) { "Nutrition source type must not be blank" }
		require(originalSourceType.length <= 80) { "Nutrition source type is too long" }
		require(provider == null || provider.isNotBlank()) { "Nutrition source provider must be null or non-blank" }
		require(provider == null || provider.length <= 120) { "Nutrition source provider is too long" }
		require(externalId == null || externalId.isNotBlank()) { "Nutrition source external ID must be null or non-blank" }
		require(externalId == null || externalId.length <= 255) { "Nutrition source external ID is too long" }
		require(notes == null || notes.isNotBlank()) { "Nutrition source notes must be null or non-blank" }
		require(notes == null || notes.length <= 1_000) { "Nutrition source notes are too long" }
		require(externalId == null || provider != null) { "Nutrition source external ID requires a provider" }
	}
}

data class DailyUserFoodSnapshot(
	val nutritionBasis: DailyFoodBasisSnapshot,
	val nutrientsPerBasis: DailyNutritionValues,
	val defaultServing: DailyFoodDefaultServingSnapshot?,
	val conversions: DailyFoodConversionSnapshot,
	val nutritionSource: DailyNutritionSourceSnapshot,
	val userFoodVersion: Long,
	val userFoodUpdatedAt: Instant,
) {
	init {
		require(userFoodVersion >= 0) { "User-food version must not be negative" }
	}
}

data class DailyFoodCaptureItem(
	val itemId: UUID,
	val sourceType: DailyFoodItemSourceType,
	val userFoodId: UUID?,
	val displayName: String,
	val brand: String?,
	val enteredQuantity: DailyEnteredQuantity,
	val resolvedQuantity: DailyResolvedQuantity,
	val userFoodSnapshot: DailyUserFoodSnapshot?,
	val calculatedNutrition: DailyNutritionValues,
) {
	init {
		require(displayName.isNotBlank()) { "Food display name must not be blank" }
		require(displayName.length <= 255) { "Food display name is too long" }
		require(brand == null || brand.isNotBlank()) { "Food brand must be null or non-blank" }
		require(brand == null || brand.length <= 120) { "Food brand is too long" }
		when (sourceType) {
			DailyFoodItemSourceType.USER_FOOD -> {
				require(userFoodId != null) { "A user-food item requires userFoodId" }
				require(userFoodSnapshot != null) { "A user-food item requires a nutrition snapshot" }
				require(userFoodSnapshot.nutritionSource.type == DailyFoodItemSourceType.USER_FOOD) {
					"User-food source snapshot is inconsistent"
				}
			}
			DailyFoodItemSourceType.MANUAL_NUTRITION,
			DailyFoodItemSourceType.AI_ESTIMATE,
			-> {
				require(userFoodId == null) { "$sourceType cannot reference userFoodId" }
				require(userFoodSnapshot == null) { "$sourceType cannot contain a user-food snapshot" }
				if (sourceType == DailyFoodItemSourceType.AI_ESTIMATE) {
					require(
						calculatedNutrition.caloriesKcal != null &&
							calculatedNutrition.proteinGrams != null &&
							calculatedNutrition.carbohydratesGrams != null &&
							calculatedNutrition.fatGrams != null,
					) { "An AI-estimated food item requires calories and all core macronutrients" }
				}
			}
		}
	}
}

data class DailyCaptureEntry(
	val entryId: UUID,
	val type: DailyCaptureEntryType,
	val mealType: DailyMealType? = null,
	val mealLabel: String? = null,
	val items: List<DailyFoodCaptureItem> = emptyList(),
	val value: BigDecimal? = null,
	val unit: DailyScalarUnit? = null,
	val text: String? = null,
	val nutritionTotal: DailyNutritionValues? = null,
) {
	init {
		require(mealLabel == null || mealLabel.isNotBlank()) { "Meal label must be null or non-blank" }
		require(mealLabel == null || mealLabel.length <= 100) { "Meal label is too long" }
		when (type) {
			DailyCaptureEntryType.FOOD -> {
				require(items.isNotEmpty()) { "A FOOD entry requires at least one item" }
				require(items.size <= 200) { "A FOOD entry cannot contain more than 200 items" }
				require(items.map(DailyFoodCaptureItem::itemId).distinct().size == items.size) {
					"Food item IDs must be unique within an entry"
				}
				require(value == null && unit == null && text == null) { "A FOOD entry cannot contain scalar fields" }
				val expected = DailyNutritionValues.strictTotal(items.map(DailyFoodCaptureItem::calculatedNutrition))
				require(nutritionTotal != null && nutritionTotal.numericallyEquals(expected)) {
					"Food-entry nutrition total is inconsistent"
				}
			}
			DailyCaptureEntryType.DAILY_NOTES,
			DailyCaptureEntryType.NOTE,
			-> {
				require(!text.isNullOrBlank()) { "$type entry requires text" }
				require(text.length <= 10_000) { "$type text is too long" }
				require(items.isEmpty() && value == null && unit == null && mealType == null && mealLabel == null) {
					"$type entry contains incompatible fields"
				}
				require(nutritionTotal == null) { "$type entry cannot contain nutrition totals" }
			}
			else -> {
				require(value != null && unit != null) { "$type entry requires value and unit" }
				require(items.isEmpty() && text == null && mealType == null && mealLabel == null) {
					"$type entry contains incompatible fields"
				}
				require(nutritionTotal == null) { "$type entry cannot contain nutrition totals" }
				validateScalarEntry(type, value, unit)
			}
		}
	}
}

internal data class DailyCaptureEntryProjection(
	val type: DailyCaptureType,
	val meals: List<MealDraft>,
	val fields: DailyFields,
	val note: String?,
)

internal fun projectDailyCaptureEntries(entries: List<DailyCaptureEntry>): DailyCaptureEntryProjection {
	require(entries.isNotEmpty()) { "A capture requires at least one entry" }
	require(entries.size <= 50) { "A capture cannot contain more than 50 entries" }
	require(entries.map(DailyCaptureEntry::entryId).distinct().size == entries.size) {
		"Capture entry IDs must be unique"
	}
	val itemIds = entries.flatMap(DailyCaptureEntry::items).map(DailyFoodCaptureItem::itemId)
	require(itemIds.distinct().size == itemIds.size) { "Food item IDs must be unique within a capture" }
	val nonRepeatable = entries.filter {
		it.type != DailyCaptureEntryType.FOOD && it.type != DailyCaptureEntryType.NOTE
	}
	require(nonRepeatable.map(DailyCaptureEntry::type).distinct().size == nonRepeatable.size) {
		"Scalar entry types must be unique within a capture"
	}
	val notes = entries.filter { it.type == DailyCaptureEntryType.NOTE }

	val meals = entries.filter { it.type == DailyCaptureEntryType.FOOD }.map { entry ->
		MealDraft(
			mealTempId = entry.entryId.toString(),
			mealName = entry.mealLabel ?: entry.mealType?.name?.lowercase(Locale.ROOT),
			items = entry.items.map { item ->
				MealItemDraft(
					itemTempId = item.itemId.toString(),
					foodName = item.displayName,
					quantity = item.resolvedQuantity.amount,
					unit = item.resolvedQuantity.unit.toProjectionUnit(),
					calories = item.calculatedNutrition.caloriesKcal,
					proteinG = item.calculatedNutrition.proteinGrams,
					carbsG = item.calculatedNutrition.carbohydratesGrams,
					fatG = item.calculatedNutrition.fatGrams,
				)
			},
		)
	}
	fun entry(type: DailyCaptureEntryType): DailyCaptureEntry? = entries.singleOrNull { it.type == type }
	val fields = DailyFields(
		bodyWeightKg = entry(DailyCaptureEntryType.WEIGHT)?.value,
		sleepHours = entry(DailyCaptureEntryType.SLEEP)?.value,
		stepsCount = entry(DailyCaptureEntryType.STEPS)?.value?.intValueExact(),
		hydrationLiters = entry(DailyCaptureEntryType.HYDRATION)?.value,
		caffeineMg = entry(DailyCaptureEntryType.CAFFEINE)?.value?.intValueExact(),
		moodLevel = entry(DailyCaptureEntryType.MOOD)?.value?.intValueExact(),
		focusLevel = entry(DailyCaptureEntryType.FOCUS)?.value?.intValueExact(),
		stressLevel = entry(DailyCaptureEntryType.STRESS)?.value?.intValueExact(),
		dailyNotes = entry(DailyCaptureEntryType.DAILY_NOTES)?.text,
	)
	val note = notes.mapNotNull(DailyCaptureEntry::text)
		.takeIf(List<String>::isNotEmpty)
		?.joinToString("\n")
	require(note == null || note.length <= 10_000) { "Combined NOTE text is too long" }
	val type = when {
		meals.isNotEmpty() && fields.hasValues() -> DailyCaptureType.MIXED
		meals.isNotEmpty() -> DailyCaptureType.FOOD
		fields.hasValues() -> DailyCaptureType.DAILY_FIELDS
		note != null -> DailyCaptureType.NOTE
		else -> error("Capture entries do not produce editable content")
	}
	return DailyCaptureEntryProjection(type, meals, fields, note)
}

private fun validateScalarEntry(type: DailyCaptureEntryType, value: BigDecimal, unit: DailyScalarUnit) {
	requireDailyNonNegativeDecimal(value, "$type value")
	when (type) {
		DailyCaptureEntryType.WEIGHT -> require(unit == DailyScalarUnit.KILOGRAM && value.isBetween("1", "500")) {
			"Weight must be between 1 and 500 kilograms"
		}
		DailyCaptureEntryType.SLEEP -> require(unit == DailyScalarUnit.HOUR && value.isBetween("0", "24")) {
			"Sleep must be between 0 and 24 hours"
		}
		DailyCaptureEntryType.STEPS -> require(unit == DailyScalarUnit.COUNT && value.isIntegerIn(0, 200_000)) {
			"Steps are outside the allowed range"
		}
		DailyCaptureEntryType.HYDRATION -> require(unit == DailyScalarUnit.LITER && value.isBetween("0", "20")) {
			"Hydration must be between 0 and 20 liters"
		}
		DailyCaptureEntryType.CAFFEINE -> require(unit == DailyScalarUnit.MILLIGRAM && value.isIntegerIn(0, 5_000)) {
			"Caffeine is outside the allowed range"
		}
		DailyCaptureEntryType.MOOD,
		DailyCaptureEntryType.FOCUS,
		DailyCaptureEntryType.STRESS,
		-> require(unit == DailyScalarUnit.LEVEL && value.isIntegerIn(1, 10)) {
			"$type level must be between 1 and 10"
		}
		else -> error("$type is not a scalar entry")
	}
}

private fun BigDecimal.isBetween(min: String, max: String): Boolean =
	this >= min.toBigDecimal() && this <= max.toBigDecimal()

private fun BigDecimal.isIntegerIn(min: Int, max: Int): Boolean =
	stripTrailingZeros().scale() <= 0 && this >= min.toBigDecimal() && this <= max.toBigDecimal()

private fun DailyResolvedFoodUnit.toProjectionUnit(): String = when (this) {
	DailyResolvedFoodUnit.GRAM -> "g"
	DailyResolvedFoodUnit.MILLILITER -> "ml"
	DailyResolvedFoodUnit.PIECE -> "unit"
	DailyResolvedFoodUnit.SERVING -> "portion"
}

internal fun BigDecimal.normalizedDailyDecimal(): BigDecimal = stripTrailingZeros().let { normalized ->
	if (normalized.scale() < 0) normalized.setScale(0) else normalized
}

internal fun requireDailyPositiveDecimal(value: BigDecimal, name: String) {
	require(value > BigDecimal.ZERO) { "$name must be positive" }
	requireDailyDecimalBounds(value, name)
}

internal fun requireDailyNonNegativeDecimal(value: BigDecimal, name: String) {
	require(value >= BigDecimal.ZERO) { "$name must not be negative" }
	requireDailyDecimalBounds(value, name)
}

private fun requireDailyDecimalBounds(value: BigDecimal, name: String) {
	require(value <= BigDecimal("1000000000000")) { "$name is too large" }
	require(value.stripTrailingZeros().scale().coerceAtLeast(0) <= 6) { "$name must have at most 6 decimal places" }
}
