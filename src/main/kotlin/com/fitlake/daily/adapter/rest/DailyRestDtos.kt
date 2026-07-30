package com.fitlake.daily.adapter.rest

import com.fitlake.daily.application.DailyDayView
import com.fitlake.daily.application.capture.DailyCaptureInput
import com.fitlake.daily.application.capture.DailyFieldsInput
import com.fitlake.daily.application.capture.MealInput
import com.fitlake.daily.application.capture.MealItemInput
import com.fitlake.daily.domain.capture.DailyCapture
import com.fitlake.daily.domain.capture.DailyCaptureActor
import com.fitlake.daily.domain.capture.DailyCaptureStatus
import com.fitlake.daily.domain.capture.DailyCaptureType
import com.fitlake.daily.domain.capture.DailyFields
import com.fitlake.daily.domain.capture.MealDraft
import com.fitlake.daily.domain.capture.MealItemDraft
import com.fitlake.daily.domain.common.DailyDayStatus
import com.fitlake.daily.domain.metrics.DailyMetrics
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Positive
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

data class DailyCaptureRequest(
	val type: DailyCaptureType,
	@field:Valid val meals: List<MealRequest> = emptyList(),
	@field:Valid val fields: DailyFieldsRequest = DailyFieldsRequest(),
	val note: String? = null,
) {
	fun toInput(): DailyCaptureInput = DailyCaptureInput(
		type = type,
		meals = meals.map(MealRequest::toInput),
		fields = fields.toInput(),
		note = note,
	)
}

data class MealRequest(
	val mealTempId: String? = null,
	val mealName: String? = null,
	@field:Valid val items: List<MealItemRequest> = emptyList(),
) {
	fun toInput(): MealInput = MealInput(
		mealTempId = mealTempId,
		mealName = mealName,
		items = items.map(MealItemRequest::toInput),
	)
}

data class MealItemRequest(
	val itemTempId: String? = null,
	@field:NotBlank val foodName: String,
	@field:Positive val quantity: BigDecimal,
	@field:NotBlank val unit: String,
	val calories: Int? = null,
	val proteinG: BigDecimal? = null,
	val carbsG: BigDecimal? = null,
	val fatG: BigDecimal? = null,
) {
	fun toInput(): MealItemInput = MealItemInput(
		itemTempId = itemTempId,
		foodName = foodName,
		quantity = quantity,
		unit = unit,
		calories = calories,
		proteinG = proteinG,
		carbsG = carbsG,
		fatG = fatG,
	)
}

data class DailyFieldsRequest(
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
	fun toInput(): DailyFieldsInput = DailyFieldsInput(
		bodyWeightKg = bodyWeightKg,
		sleepHours = sleepHours,
		stepsCount = stepsCount,
		hydrationLiters = hydrationLiters,
		caffeineMg = caffeineMg,
		moodLevel = moodLevel,
		focusLevel = focusLevel,
		stressLevel = stressLevel,
		dailyNotes = dailyNotes,
	)
}

data class UpdateFoodItemRequest(
	@field:Positive val quantity: BigDecimal,
	@field:NotBlank val unit: String,
)

data class MealItemResponse(
	val itemTempId: String,
	val foodName: String,
	val quantity: BigDecimal,
	val unit: String,
	val calories: Int?,
	val proteinG: BigDecimal?,
	val carbsG: BigDecimal?,
	val fatG: BigDecimal?,
)

data class MealResponse(
	val mealTempId: String,
	val mealName: String?,
	val items: List<MealItemResponse>,
)

data class DailyFieldsResponse(
	val bodyWeightKg: BigDecimal?,
	val sleepHours: BigDecimal?,
	val stepsCount: Int?,
	val hydrationLiters: BigDecimal?,
	val caffeineMg: Int?,
	val moodLevel: Int?,
	val focusLevel: Int?,
	val stressLevel: Int?,
	val dailyNotes: String?,
)

data class DailyCapturePayloadResponse(
	val type: DailyCaptureType,
	val meals: List<MealResponse>,
	val fields: DailyFieldsResponse,
	val note: String?,
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
	val totalCalories: Int?,
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
	val version: Long,
	val captures: List<DailyCaptureResponse>,
	val metrics: DailyMetricsResponse?,
)

fun DailyCapture.toResponse(): DailyCaptureResponse = DailyCaptureResponse(
	captureId = captureId.value,
	dayId = dayId.value,
	captureType = captureType,
	status = status,
	payload = DailyCapturePayloadResponse(
		type = payload.type,
		meals = payload.meals.map(MealDraft::toResponse),
		fields = payload.fields.toResponse(),
		note = payload.note,
	),
	createdBy = createdBy,
	updatedBy = updatedBy,
	acceptedAt = acceptedAt,
	rejectedAt = rejectedAt,
	deletedAt = deletedAt,
	createdAt = createdAt,
	updatedAt = updatedAt,
	version = version,
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

private fun DailyFields.toResponse(): DailyFieldsResponse = DailyFieldsResponse(
	bodyWeightKg = bodyWeightKg,
	sleepHours = sleepHours,
	stepsCount = stepsCount,
	hydrationLiters = hydrationLiters,
	caffeineMg = caffeineMg,
	moodLevel = moodLevel,
	focusLevel = focusLevel,
	stressLevel = stressLevel,
	dailyNotes = dailyNotes,
)
