package com.fitlake.daily.application.ai

import com.fitlake.daily.application.DailyStateCorruptionException
import com.fitlake.daily.domain.capture.DailyCapture
import com.fitlake.daily.domain.capture.DailyCaptureActor
import com.fitlake.daily.domain.capture.DailyCaptureId
import com.fitlake.daily.domain.capture.DailyCapturePayload
import com.fitlake.daily.domain.capture.DailyCaptureStatus
import com.fitlake.daily.domain.capture.DailyCaptureType
import com.fitlake.daily.domain.capture.DailyFields
import com.fitlake.daily.domain.capture.MealDraft
import com.fitlake.daily.domain.capture.MealItemDraft
import com.fitlake.daily.domain.common.DailyDayId
import com.fitlake.daily.domain.inbox.DailyInboxEvent
import com.fitlake.user.domain.UserId
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

/** Immutable representation of the capture returned by an idempotent AI operation. */
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

internal fun DailyAiCaptureResult.toAuditOutput(outcome: String): Map<String, Any?> = linkedMapOf(
	"outcome" to outcome,
	"capture" to linkedMapOf(
		"captureId" to captureId.value.toString(),
		"userId" to userId.value.toString(),
		"dayId" to dayId.value.toString(),
		"sourceEventId" to sourceEventId.toString(),
		"captureType" to captureType.name,
		"status" to status.name,
		"payload" to payload.toAuditMap(),
		"createdBy" to createdBy.name,
		"createdAt" to createdAt.toString(),
		"version" to version,
	),
)

internal fun Map<String, Any?>.captureResult(
	event: DailyInboxEvent,
	expectedCaptureId: DailyCaptureId,
	confidence: BigDecimal?,
): DailyAiCaptureResult = try {
	val capture = requiredMap("capture")
	val result = DailyAiCaptureResult(
		captureId = DailyCaptureId(UUID.fromString(capture.requiredString("captureId"))),
		userId = UserId(UUID.fromString(capture.requiredString("userId"))),
		dayId = DailyDayId(UUID.fromString(capture.requiredString("dayId"))),
		sourceEventId = UUID.fromString(capture.requiredString("sourceEventId")),
		captureType = DailyCaptureType.valueOf(capture.requiredString("captureType")),
		status = DailyCaptureStatus.valueOf(capture.requiredString("status")),
		payload = capture.requiredMap("payload").toPayload(),
		confidence = confidence,
		createdBy = DailyCaptureActor.valueOf(capture.requiredString("createdBy")),
		createdAt = Instant.parse(capture.requiredString("createdAt")),
		version = capture.long("version") ?: error("Missing capture version"),
	)
	if (
		result.captureId != expectedCaptureId ||
		result.userId != event.userId ||
		result.dayId != event.dayId ||
		result.sourceEventId != event.inboxEventId.value ||
		result.status != DailyCaptureStatus.OPEN ||
		result.createdBy != DailyCaptureActor.AI ||
		result.captureType != result.payload.type
	) {
		throw IllegalStateException("AI capture audit snapshot violates backend invariants")
	}
	result
} catch (exception: DailyStateCorruptionException) {
	throw exception
} catch (exception: RuntimeException) {
	throw DailyStateCorruptionException("AI capture audit snapshot is invalid")
}

private fun DailyCapturePayload.toAuditMap(): Map<String, Any?> = linkedMapOf(
	"type" to type.name,
	"meals" to meals.map(MealDraft::toAuditMap),
	"fields" to fields.toAuditMap(),
	"note" to note,
)

private fun MealDraft.toAuditMap(): Map<String, Any?> = linkedMapOf(
	"mealTempId" to mealTempId,
	"mealName" to mealName,
	"items" to items.map(MealItemDraft::toAuditMap),
)

private fun MealItemDraft.toAuditMap(): Map<String, Any?> = linkedMapOf(
	"itemTempId" to itemTempId,
	"foodName" to foodName,
	"quantity" to quantity,
	"unit" to unit,
	"calories" to calories,
	"proteinG" to proteinG,
	"carbsG" to carbsG,
	"fatG" to fatG,
)

private fun DailyFields.toAuditMap(): Map<String, Any?> = linkedMapOf(
	"bodyWeightKg" to bodyWeightKg,
	"sleepHours" to sleepHours,
	"stepsCount" to stepsCount,
	"hydrationLiters" to hydrationLiters,
	"caffeineMg" to caffeineMg,
	"moodLevel" to moodLevel,
	"focusLevel" to focusLevel,
	"stressLevel" to stressLevel,
	"dailyNotes" to dailyNotes,
)

private fun Map<String, Any?>.toPayload(): DailyCapturePayload = DailyCapturePayload(
	type = DailyCaptureType.valueOf(requiredString("type")),
	meals = listOfMaps("meals").map { meal ->
		MealDraft(
			mealTempId = meal.requiredString("mealTempId"),
			mealName = meal.optionalString("mealName"),
			items = meal.listOfMaps("items").map { item ->
				MealItemDraft(
					itemTempId = item.requiredString("itemTempId"),
					foodName = item.requiredString("foodName"),
					quantity = item.decimal("quantity") ?: error("Missing food quantity"),
					unit = item.requiredString("unit"),
					calories = item.int("calories"),
					proteinG = item.decimal("proteinG"),
					carbsG = item.decimal("carbsG"),
					fatG = item.decimal("fatG"),
				)
			},
		)
	},
	fields = requiredMap("fields").let { fields ->
		DailyFields(
			bodyWeightKg = fields.decimal("bodyWeightKg"),
			sleepHours = fields.decimal("sleepHours"),
			stepsCount = fields.int("stepsCount"),
			hydrationLiters = fields.decimal("hydrationLiters"),
			caffeineMg = fields.int("caffeineMg"),
			moodLevel = fields.int("moodLevel"),
			focusLevel = fields.int("focusLevel"),
			stressLevel = fields.int("stressLevel"),
			dailyNotes = fields.optionalString("dailyNotes"),
		)
	},
	note = optionalString("note"),
)

private fun Map<String, Any?>.requiredString(key: String): String =
	(this[key] as? String)?.takeIf(String::isNotBlank) ?: error("Missing field $key")

private fun Map<String, Any?>.optionalString(key: String): String? = this[key] as? String

@Suppress("UNCHECKED_CAST")
private fun Map<String, Any?>.requiredMap(key: String): Map<String, Any?> =
	this[key] as? Map<String, Any?> ?: error("Missing field $key")

@Suppress("UNCHECKED_CAST")
private fun Map<String, Any?>.listOfMaps(key: String): List<Map<String, Any?>> =
	this[key] as? List<Map<String, Any?>> ?: emptyList()

private fun Map<String, Any?>.decimal(key: String): BigDecimal? = when (val value = this[key]) {
	null -> null
	is BigDecimal -> value
	is Number -> value.toString().toBigDecimal()
	is String -> value.toBigDecimal()
	else -> error("Invalid decimal field $key")
}

private fun Map<String, Any?>.int(key: String): Int? = when (val value = this[key]) {
	null -> null
	is Number -> value.toInt()
	is String -> value.toInt()
	else -> error("Invalid integer field $key")
}

private fun Map<String, Any?>.long(key: String): Long? = when (val value = this[key]) {
	null -> null
	is Number -> value.toLong()
	is String -> value.toLong()
	else -> error("Invalid long field $key")
}
