package com.fitlake.daily.application.capture

import com.fitlake.daily.domain.capture.DailyCaptureEntryType
import com.fitlake.daily.domain.capture.DailyFoodItemSourceType
import com.fitlake.daily.domain.capture.DailyFoodQuantityUnit
import com.fitlake.daily.domain.capture.DailyMealType
import com.fitlake.daily.domain.capture.DailyScalarUnit
import java.math.BigDecimal
import java.util.UUID

data class DailyCaptureContentInput(
	val entries: List<DailyCaptureEntryInput>,
)

data class DailyCaptureEntryInput(
	val entryId: UUID?,
	val type: DailyCaptureEntryType,
	val mealType: DailyMealType? = null,
	val mealLabel: String? = null,
	val items: List<DailyFoodItemInput> = emptyList(),
	val value: BigDecimal? = null,
	val unit: DailyScalarUnit? = null,
	val text: String? = null,
)

data class DailyFoodItemInput(
	val itemId: UUID?,
	val sourceType: DailyFoodItemSourceType,
	val userFoodId: UUID?,
	val quantity: DailyEnteredFoodQuantityInput,
)

data class DailyEnteredFoodQuantityInput(
	val amount: BigDecimal,
	val unit: DailyFoodQuantityUnit,
)

