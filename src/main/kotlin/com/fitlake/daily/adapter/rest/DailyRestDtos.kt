package com.fitlake.daily.adapter.rest

import com.fitlake.daily.application.DailyDayView
import com.fitlake.daily.application.capture.DailyCaptureContentInput
import com.fitlake.daily.application.capture.DailyCaptureEntryInput
import com.fitlake.daily.application.capture.DailyEnteredFoodQuantityInput
import com.fitlake.daily.application.capture.DailyFoodItemInput
import com.fitlake.daily.domain.capture.DailyCapture
import com.fitlake.daily.domain.capture.DailyCaptureActor
import com.fitlake.daily.domain.capture.DailyCaptureEntry
import com.fitlake.daily.domain.capture.DailyCaptureEntryType
import com.fitlake.daily.domain.capture.DailyCaptureStatus
import com.fitlake.daily.domain.capture.DailyCaptureType
import com.fitlake.daily.domain.capture.DailyCapturePayload
import com.fitlake.daily.domain.capture.DailyEnteredQuantity
import com.fitlake.daily.domain.capture.DailyFoodBasisSnapshot
import com.fitlake.daily.domain.capture.DailyFoodCaptureItem
import com.fitlake.daily.domain.capture.DailyFoodConversionSnapshot
import com.fitlake.daily.domain.capture.DailyFoodDefaultServingSnapshot
import com.fitlake.daily.domain.capture.DailyFoodItemSourceType
import com.fitlake.daily.domain.capture.DailyFoodQuantityUnit
import com.fitlake.daily.domain.capture.DailyFoodSnapshotUnit
import com.fitlake.daily.domain.capture.DailyMealType
import com.fitlake.daily.domain.capture.DailyNutritionSourceSnapshot
import com.fitlake.daily.domain.capture.DailyNutritionValues
import com.fitlake.daily.domain.capture.DailyResolvedFoodUnit
import com.fitlake.daily.domain.capture.DailyResolvedQuantity
import com.fitlake.daily.domain.capture.DailyScalarUnit
import com.fitlake.daily.domain.capture.MealDraft
import com.fitlake.daily.domain.capture.MealItemDraft
import com.fitlake.daily.domain.common.DailyDayStatus
import com.fitlake.daily.domain.metrics.DailyMetrics
import jakarta.validation.Valid
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Positive
import jakarta.validation.constraints.PositiveOrZero
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

data class DailyCaptureRequest(
	@field:Valid val entries: List<DailyCaptureEntryRequest>,
) {
	fun toContentInput() = DailyCaptureContentInput(entries.map(DailyCaptureEntryRequest::toInput))
}

data class ReplaceDailyCaptureRequest(
	@field:NotNull @field:PositiveOrZero val version: Long?,
	@field:Valid val entries: List<DailyCaptureEntryRequest>,
) {
	fun toInput() = DailyCaptureContentInput(entries.map(DailyCaptureEntryRequest::toInput))

	fun requiredVersion(): Long = requireNotNull(version) { "Capture version is required" }
}

data class DailyCaptureEntryRequest(
	val entryId: UUID? = null,
	val type: DailyCaptureEntryType,
	val mealType: DailyMealType? = null,
	val mealLabel: String? = null,
	@field:Valid val items: List<DailyFoodItemRequest> = emptyList(),
	val value: BigDecimal? = null,
	val amount: BigDecimal? = null,
	val unit: DailyScalarUnit? = null,
	val text: String? = null,
) {
	fun toInput(): DailyCaptureEntryInput {
		require(value == null || amount == null) { "Use either value or amount, not both" }
		return DailyCaptureEntryInput(
			entryId = entryId,
			type = type,
			mealType = mealType,
			mealLabel = mealLabel,
			items = items.map(DailyFoodItemRequest::toInput),
			value = value ?: amount,
			unit = unit,
			text = text,
		)
	}
}

data class DailyFoodItemRequest(
	val itemId: UUID? = null,
	val sourceType: DailyFoodItemRequestSourceType,
	val userFoodId: UUID?,
	@field:Valid val quantity: DailyFoodQuantityRequest,
) {
	fun toInput() = DailyFoodItemInput(
		itemId,
		when (sourceType) {
			DailyFoodItemRequestSourceType.USER_FOOD -> DailyFoodItemSourceType.USER_FOOD
			DailyFoodItemRequestSourceType.AI_ESTIMATE -> DailyFoodItemSourceType.AI_ESTIMATE
		},
		userFoodId,
		quantity.toInput(),
	)
}

enum class DailyFoodItemRequestSourceType {
	USER_FOOD,
	AI_ESTIMATE,
}

data class DailyFoodQuantityRequest(
	@field:Positive val amount: BigDecimal,
	val unit: DailyFoodQuantityUnit,
) {
	fun toInput() = DailyEnteredFoodQuantityInput(amount, unit)
}

data class MealItemResponse(
	val itemTempId: String,
	val foodName: String,
	val quantity: BigDecimal,
	val unit: String,
	val calories: BigDecimal?,
	val proteinG: BigDecimal?,
	val carbsG: BigDecimal?,
	val fatG: BigDecimal?,
)

data class MealResponse(
	val mealTempId: String,
	val mealName: String?,
	val items: List<MealItemResponse>,
)

data class DailyCapturePayloadResponse(
	val schemaVersion: Int,
	val entries: List<DailyCaptureEntryResponse>,
)

data class DailyCaptureEntryResponse(
	val entryId: UUID,
	val type: DailyCaptureEntryType,
	val mealType: DailyMealType?,
	val mealLabel: String?,
	val items: List<DailyFoodItemResponse>,
	val value: BigDecimal?,
	val unit: DailyScalarUnit?,
	val text: String?,
	val nutritionTotal: DailyNutritionValuesResponse?,
)

data class DailyFoodItemResponse(
	val itemId: UUID,
	val sourceType: DailyFoodItemSourceType,
	val userFoodId: UUID?,
	val displayName: String,
	val brand: String?,
	val enteredQuantity: DailyEnteredQuantityResponse,
	val resolvedQuantity: DailyResolvedQuantityResponse,
	val nutritionBasisSnapshot: DailyFoodBasisSnapshotResponse?,
	val nutrientsPerBasisSnapshot: DailyNutritionValuesResponse?,
	val defaultServingSnapshot: DailyFoodDefaultServingSnapshotResponse?,
	val conversionSnapshot: DailyFoodConversionSnapshotResponse?,
	val calculatedNutrition: DailyNutritionValuesResponse,
	val nutritionSourceSnapshot: DailyNutritionSourceSnapshotResponse?,
	val userFoodVersion: Long?,
	val userFoodUpdatedAt: Instant?,
)

data class DailyEnteredQuantityResponse(val amount: BigDecimal, val unit: DailyFoodQuantityUnit)

data class DailyResolvedQuantityResponse(val amount: BigDecimal, val unit: DailyResolvedFoodUnit)

data class DailyFoodBasisSnapshotResponse(val amount: BigDecimal, val unit: DailyFoodSnapshotUnit)

data class DailyFoodDefaultServingSnapshotResponse(val amount: BigDecimal, val unit: DailyFoodSnapshotUnit)

data class DailyFoodConversionSnapshotResponse(
	val gramsPerPiece: BigDecimal?,
	val millilitersPerPiece: BigDecimal?,
	val gramsPerServing: BigDecimal?,
	val millilitersPerServing: BigDecimal?,
)

data class DailyNutritionSourceSnapshotResponse(
	val type: DailyFoodItemSourceType,
	val originalSourceType: String,
	val estimated: Boolean,
	val provider: String?,
	val externalId: String?,
	val notes: String?,
	val copiedAt: LocalDate?,
)

data class DailyNutritionValuesResponse(
	val caloriesKcal: BigDecimal?,
	val proteinGrams: BigDecimal?,
	val carbohydratesGrams: BigDecimal?,
	val fatGrams: BigDecimal?,
	val fiberGrams: BigDecimal?,
	val sugarsGrams: BigDecimal?,
	val saturatedFatGrams: BigDecimal?,
	val sodiumMilligrams: BigDecimal?,
	val saltGrams: BigDecimal?,
)

data class DailyCaptureResponse(
	val captureId: UUID,
	val dayId: UUID,
	val captureType: DailyCaptureType,
	val status: DailyCaptureStatus,
	val payload: DailyCapturePayloadResponse,
	val createdBy: DailyCaptureActor,
	val updatedBy: DailyCaptureActor?,
	val acceptedAt: Instant?,
	val rejectedAt: Instant?,
	val deletedAt: Instant?,
	val createdAt: Instant,
	val updatedAt: Instant,
	val version: Long,
)

data class DailyMetricsResponse(
	val dayId: UUID,
	val dayDate: LocalDate,
	val status: DailyDayStatus,
	val bodyWeightKg: BigDecimal?,
	val sleepHours: BigDecimal?,
	val stepsCount: Int?,
	val hydrationLiters: BigDecimal?,
	val caffeineMg: Int?,
	val moodLevel: Int?,
	val focusLevel: Int?,
	val stressLevel: Int?,
	val totalCalories: BigDecimal?,
	val proteinG: BigDecimal?,
	val carbsG: BigDecimal?,
	val fatG: BigDecimal?,
	val foodLog: List<MealResponse>,
	val dailyNotes: String?,
	val generatedFromCaptureIds: List<UUID>,
	val confirmedAt: Instant?,
	val recalculatedAt: Instant?,
)

data class DailyDayResponse(
	val dayId: UUID,
	val dayDate: LocalDate,
	val status: DailyDayStatus,
	val openedAt: Instant,
	val confirmedAt: Instant?,
	val reopenedAt: Instant?,
	val version: Long,
	val captures: List<DailyCaptureResponse>,
	val metrics: DailyMetricsResponse?,
)

fun DailyCapture.toResponse(): DailyCaptureResponse = DailyCaptureResponse(
	captureId = captureId.value,
	dayId = dayId.value,
	captureType = captureType,
	status = status,
	payload = payload.toResponse(),
	createdBy = createdBy,
	updatedBy = updatedBy,
	acceptedAt = acceptedAt,
	rejectedAt = rejectedAt,
	deletedAt = deletedAt,
	createdAt = createdAt,
	updatedAt = updatedAt,
	version = version,
)

internal fun DailyCapturePayload.toResponse(): DailyCapturePayloadResponse = DailyCapturePayloadResponse(
	schemaVersion = schemaVersion,
	entries = entries.map(DailyCaptureEntry::toResponse),
)

fun DailyMetrics.toResponse(): DailyMetricsResponse = DailyMetricsResponse(
	dayId = dayId.value,
	dayDate = dayDate,
	status = status,
	bodyWeightKg = bodyWeightKg,
	sleepHours = sleepHours,
	stepsCount = stepsCount,
	hydrationLiters = hydrationLiters,
	caffeineMg = caffeineMg,
	moodLevel = moodLevel,
	focusLevel = focusLevel,
	stressLevel = stressLevel,
	totalCalories = totalCalories,
	proteinG = proteinG,
	carbsG = carbsG,
	fatG = fatG,
	foodLog = foodLog.map(MealDraft::toResponse),
	dailyNotes = dailyNotes,
	generatedFromCaptureIds = generatedFromCaptureIds,
	confirmedAt = confirmedAt,
	recalculatedAt = recalculatedAt,
)

fun DailyDayView.toResponse(): DailyDayResponse = DailyDayResponse(
	dayId = day.dayId.value,
	dayDate = day.dayDate,
	status = day.status,
	openedAt = day.openedAt,
	confirmedAt = day.confirmedAt,
	reopenedAt = day.reopenedAt,
	version = day.version,
	captures = captures.map(DailyCapture::toResponse),
	metrics = metrics?.toResponse(),
)

private fun MealDraft.toResponse(): MealResponse = MealResponse(
	mealTempId = mealTempId,
	mealName = mealName,
	items = items.map(MealItemDraft::toResponse),
)

private fun MealItemDraft.toResponse(): MealItemResponse = MealItemResponse(
	itemTempId = itemTempId,
	foodName = foodName,
	quantity = quantity,
	unit = unit,
	calories = calories,
	proteinG = proteinG,
	carbsG = carbsG,
	fatG = fatG,
)

private fun DailyCaptureEntry.toResponse() = DailyCaptureEntryResponse(
	entryId = entryId,
	type = type,
	mealType = mealType,
	mealLabel = mealLabel,
	items = items.map(DailyFoodCaptureItem::toResponse),
	value = value,
	unit = unit,
	text = text,
	nutritionTotal = nutritionTotal?.toResponse(),
)

private fun DailyFoodCaptureItem.toResponse(): DailyFoodItemResponse {
	val snapshot = userFoodSnapshot
	return DailyFoodItemResponse(
		itemId = itemId,
		sourceType = sourceType,
		userFoodId = userFoodId,
		displayName = displayName,
		brand = brand,
		enteredQuantity = enteredQuantity.toResponse(),
		resolvedQuantity = resolvedQuantity.toResponse(),
		nutritionBasisSnapshot = snapshot?.nutritionBasis?.toResponse(),
		nutrientsPerBasisSnapshot = snapshot?.nutrientsPerBasis?.toResponse(),
		defaultServingSnapshot = snapshot?.defaultServing?.toResponse(),
		conversionSnapshot = snapshot?.conversions?.toResponse(),
		calculatedNutrition = calculatedNutrition.toResponse(),
		nutritionSourceSnapshot = snapshot?.nutritionSource?.toResponse(),
		userFoodVersion = snapshot?.userFoodVersion,
		userFoodUpdatedAt = snapshot?.userFoodUpdatedAt,
	)
}

private fun DailyEnteredQuantity.toResponse() = DailyEnteredQuantityResponse(amount, unit)

private fun DailyResolvedQuantity.toResponse() = DailyResolvedQuantityResponse(amount, unit)

private fun DailyFoodBasisSnapshot.toResponse() = DailyFoodBasisSnapshotResponse(amount, unit)

private fun DailyFoodDefaultServingSnapshot.toResponse() = DailyFoodDefaultServingSnapshotResponse(amount, unit)

private fun DailyFoodConversionSnapshot.toResponse() = DailyFoodConversionSnapshotResponse(
	gramsPerPiece,
	millilitersPerPiece,
	gramsPerServing,
	millilitersPerServing,
)

private fun DailyNutritionSourceSnapshot.toResponse() = DailyNutritionSourceSnapshotResponse(
	type,
	originalSourceType,
	estimated,
	provider,
	externalId,
	notes,
	copiedAt,
)

private fun DailyNutritionValues.toResponse() = DailyNutritionValuesResponse(
	caloriesKcal,
	proteinGrams,
	carbohydratesGrams,
	fatGrams,
	fiberGrams,
	sugarsGrams,
	saturatedFatGrams,
	sodiumMilligrams,
	saltGrams,
)
