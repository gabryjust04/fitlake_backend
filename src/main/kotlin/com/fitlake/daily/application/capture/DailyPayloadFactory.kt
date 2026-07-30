package com.fitlake.daily.application.capture

import com.fitlake.daily.application.DailyValidationException
import com.fitlake.daily.domain.capture.DailyCapturePayload
import com.fitlake.daily.domain.capture.DailyFields
import com.fitlake.daily.domain.capture.FoodUnitNormalizer
import com.fitlake.daily.domain.capture.MealDraft
import com.fitlake.daily.domain.capture.MealItemDraft
import org.springframework.stereotype.Component
import java.util.UUID

@Component
class DailyPayloadFactory {
	fun create(input: DailyCaptureInput): DailyCapturePayload = try {
		DailyCapturePayload(
			type = input.type,
			meals = input.meals.map { meal ->
				MealDraft(
					mealTempId = reference(meal.mealTempId, "meal"),
					mealName = meal.mealName.normalizedOrNull(),
					items = meal.items.map { item ->
						MealItemDraft(
							itemTempId = reference(item.itemTempId, "item"),
							foodName = item.foodName.trim(),
							quantity = item.quantity,
							unit = FoodUnitNormalizer.normalize(item.unit),
							calories = item.calories,
							proteinG = item.proteinG,
							carbsG = item.carbsG,
							fatG = item.fatG,
						)
					},
				)
			},
			fields = DailyFields(
				bodyWeightKg = input.fields.bodyWeightKg,
				sleepHours = input.fields.sleepHours,
				stepsCount = input.fields.stepsCount,
				hydrationLiters = input.fields.hydrationLiters,
				caffeineMg = input.fields.caffeineMg,
				moodLevel = input.fields.moodLevel,
				focusLevel = input.fields.focusLevel,
				stressLevel = input.fields.stressLevel,
				dailyNotes = input.fields.dailyNotes.normalizedOrNull(),
			),
			note = input.note.normalizedOrNull(),
		)
	} catch (exception: IllegalArgumentException) {
		throw DailyValidationException(exception.message ?: "Invalid daily capture payload")
	}

	private fun reference(value: String?, prefix: String): String =
		value.normalizedOrNull() ?: "${prefix}_${UUID.randomUUID()}"

	private fun String?.normalizedOrNull(): String? = this?.trim()?.takeIf(String::isNotEmpty)
}
