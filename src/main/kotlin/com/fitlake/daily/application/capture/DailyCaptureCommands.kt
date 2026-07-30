package com.fitlake.daily.application.capture

import com.fitlake.daily.domain.capture.DailyCaptureType
import java.math.BigDecimal

data class MealItemInput(
	val itemTempId: String?,
	val foodName: String,
	val quantity: BigDecimal,
	val unit: String,
	val calories: Int?,
	val proteinG: BigDecimal?,
	val carbsG: BigDecimal?,
	val fatG: BigDecimal?,
)

data class MealInput(
	val mealTempId: String?,
	val mealName: String?,
	val items: List<MealItemInput>,
)

data class DailyFieldsInput(
	val bodyWeightKg: BigDecimal? = null,
	val sleepHours: BigDecimal? = null,
	val stepsCount: Int? = null,
	val hydrationLiters: BigDecimal? = null,
	val caffeineMg: Int? = null,
	val moodLevel: Int? = null,
	val focusLevel: Int? = null,
	val stressLevel: Int? = null,
	val dailyNotes: String? = null,
)

data class DailyCaptureInput(
	val type: DailyCaptureType,
	val meals: List<MealInput> = emptyList(),
	val fields: DailyFieldsInput = DailyFieldsInput(),
	val note: String? = null,
)
