package com.fitlake.daily.application.ai

import com.fitlake.daily.application.DailyStateCorruptionException
import com.fitlake.daily.domain.capture.DailyCapture
import com.fitlake.daily.domain.capture.DailyCaptureActor
import com.fitlake.daily.domain.capture.DailyCaptureId
import com.fitlake.daily.domain.capture.DailyCapturePayload
import com.fitlake.daily.domain.capture.DailyCaptureStatus
import com.fitlake.daily.domain.capture.DailyCaptureType
import com.fitlake.daily.domain.common.DailyDayId
import com.fitlake.user.domain.UserId
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

/** Backend-owned capture representation returned by a new or idempotently replayed AI operation. */
data class DailyAiCaptureResult(
	val captureId: DailyCaptureId,
	val userId: UserId,
	val dayId: DailyDayId,
	val sourceEventId: UUID,
	val captureType: DailyCaptureType,
	val status: DailyCaptureStatus,
	val payload: DailyCapturePayload,
	val confidence: BigDecimal?,
	val createdBy: DailyCaptureActor,
	val createdAt: Instant,
	val version: Long,
)

internal fun DailyCapture.toAiCaptureResult(): DailyAiCaptureResult {
	val sourceEventId = sourceEventId
		?: throw DailyStateCorruptionException("AI capture has no source event")
	return DailyAiCaptureResult(
		captureId = captureId,
		userId = userId,
		dayId = dayId,
		sourceEventId = sourceEventId,
		captureType = captureType,
		status = status,
		payload = payload,
		confidence = confidence,
		createdBy = createdBy,
		createdAt = createdAt,
		version = version,
	)
}

internal fun DailyAiCaptureResult.toSafeAuditOutput(
	outcome: String,
	interpretationOutcome: DailyMessageInterpretationOutcome,
	nutritionResolutions: List<DailyAiNutritionResolution> = emptyList(),
): Map<String, Any?> = linkedMapOf<String, Any?>(
	"outcome" to outcome,
	"interpretationOutcome" to interpretationOutcome.name,
	"captureId" to captureId.value.toString(),
	"captureType" to captureType.name,
	"entryCount" to payload.entries.size,
	"foodItemCount" to payload.entries.sumOf { it.items.size },
	"catalogMatchCount" to nutritionResolutions.count {
		it.outcome == DailyAiNutritionResolutionOutcome.CATALOG_MATCH
	},
	"aiFallbackCount" to nutritionResolutions.count {
		it.outcome != DailyAiNutritionResolutionOutcome.CATALOG_MATCH
	},
	"unresolvedFragmentCount" to payload.entries.count {
		it.type == com.fitlake.daily.domain.capture.DailyCaptureEntryType.NOTE
	},
).also { output ->
	if (nutritionResolutions.isNotEmpty()) {
		output["nutritionResolutions"] = nutritionResolutions.map(DailyAiNutritionResolution::toAuditMap)
	}
}
