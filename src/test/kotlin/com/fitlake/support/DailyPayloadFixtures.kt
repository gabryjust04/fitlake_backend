package com.fitlake.support

import com.fitlake.daily.domain.capture.DailyCaptureEntry
import com.fitlake.daily.domain.capture.DailyCaptureEntryType
import com.fitlake.daily.domain.capture.DailyCapturePayload
import com.fitlake.daily.domain.capture.DailyEnteredQuantity
import com.fitlake.daily.domain.capture.DailyFoodCaptureItem
import com.fitlake.daily.domain.capture.DailyFoodItemSourceType
import com.fitlake.daily.domain.capture.DailyFoodQuantityUnit
import com.fitlake.daily.domain.capture.DailyMealType
import com.fitlake.daily.domain.capture.DailyNutritionValues
import com.fitlake.daily.domain.capture.DailyResolvedFoodUnit
import com.fitlake.daily.domain.capture.DailyResolvedQuantity
import com.fitlake.daily.domain.capture.DailyScalarUnit
import java.math.BigDecimal
import java.util.UUID

fun dailyFieldsPayload(
	bodyWeightKg: BigDecimal? = null,
	sleepHours: BigDecimal? = null,
	stepsCount: Int? = null,
	hydrationLiters: BigDecimal? = null,
	caffeineMg: Int? = null,
	moodLevel: Int? = null,
	focusLevel: Int? = null,
	stressLevel: Int? = null,
	dailyNotes: String? = null,
): DailyCapturePayload = DailyCapturePayload.fromEntries(buildList {
	bodyWeightKg?.let { add(scalarEntry(DailyCaptureEntryType.WEIGHT, it, DailyScalarUnit.KILOGRAM)) }
	sleepHours?.let { add(scalarEntry(DailyCaptureEntryType.SLEEP, it, DailyScalarUnit.HOUR)) }
	stepsCount?.let { add(scalarEntry(DailyCaptureEntryType.STEPS, it.toBigDecimal(), DailyScalarUnit.COUNT)) }
	hydrationLiters?.let { add(scalarEntry(DailyCaptureEntryType.HYDRATION, it, DailyScalarUnit.LITER)) }
	caffeineMg?.let { add(scalarEntry(DailyCaptureEntryType.CAFFEINE, it.toBigDecimal(), DailyScalarUnit.MILLIGRAM)) }
	moodLevel?.let { add(scalarEntry(DailyCaptureEntryType.MOOD, it.toBigDecimal(), DailyScalarUnit.LEVEL)) }
	focusLevel?.let { add(scalarEntry(DailyCaptureEntryType.FOCUS, it.toBigDecimal(), DailyScalarUnit.LEVEL)) }
	stressLevel?.let { add(scalarEntry(DailyCaptureEntryType.STRESS, it.toBigDecimal(), DailyScalarUnit.LEVEL)) }
	dailyNotes?.let { add(DailyCaptureEntry(UUID.randomUUID(), DailyCaptureEntryType.DAILY_NOTES, text = it)) }
})

fun dailyNotePayload(note: String): DailyCapturePayload = DailyCapturePayload.fromEntries(
	listOf(DailyCaptureEntry(UUID.randomUUID(), DailyCaptureEntryType.NOTE, text = note)),
)

fun dailyFoodPayload(
	items: List<DailyFoodCaptureItem>,
	mealType: DailyMealType? = null,
	mealLabel: String? = null,
): DailyCapturePayload = DailyCapturePayload.fromEntries(
	listOf(
		DailyCaptureEntry(
			entryId = UUID.randomUUID(),
			type = DailyCaptureEntryType.FOOD,
			mealType = mealType,
			mealLabel = mealLabel,
			items = items,
			nutritionTotal = DailyNutritionValues.strictTotal(items.map(DailyFoodCaptureItem::calculatedNutrition)),
		),
	),
)

fun manualNutritionItem(
	foodName: String,
	quantity: BigDecimal,
	unit: DailyFoodQuantityUnit,
	calories: BigDecimal? = null,
	protein: BigDecimal? = null,
	carbohydrates: BigDecimal? = null,
	fat: BigDecimal? = null,
): DailyFoodCaptureItem {
	val resolved = when (unit) {
		DailyFoodQuantityUnit.GRAM -> DailyResolvedQuantity(quantity, DailyResolvedFoodUnit.GRAM)
		DailyFoodQuantityUnit.KILOGRAM -> DailyResolvedQuantity(quantity.multiply(BigDecimal("1000")), DailyResolvedFoodUnit.GRAM)
		DailyFoodQuantityUnit.MILLILITER -> DailyResolvedQuantity(quantity, DailyResolvedFoodUnit.MILLILITER)
		DailyFoodQuantityUnit.LITER -> DailyResolvedQuantity(quantity.multiply(BigDecimal("1000")), DailyResolvedFoodUnit.MILLILITER)
		DailyFoodQuantityUnit.PIECE -> DailyResolvedQuantity(quantity, DailyResolvedFoodUnit.PIECE)
		DailyFoodQuantityUnit.SERVING -> DailyResolvedQuantity(quantity, DailyResolvedFoodUnit.SERVING)
		DailyFoodQuantityUnit.DEFAULT_SERVING -> error("A fixture without a catalog snapshot cannot use DEFAULT_SERVING")
	}
	return DailyFoodCaptureItem(
		itemId = UUID.randomUUID(),
		sourceType = DailyFoodItemSourceType.MANUAL_NUTRITION,
		userFoodId = null,
		displayName = foodName,
		brand = null,
		enteredQuantity = DailyEnteredQuantity(quantity, unit),
		resolvedQuantity = resolved,
		userFoodSnapshot = null,
		calculatedNutrition = DailyNutritionValues(
			caloriesKcal = calories,
			proteinGrams = protein,
			carbohydratesGrams = carbohydrates,
			fatGrams = fat,
		),
	)
}

private fun scalarEntry(type: DailyCaptureEntryType, value: BigDecimal, unit: DailyScalarUnit) =
	DailyCaptureEntry(UUID.randomUUID(), type, value = value, unit = unit)
