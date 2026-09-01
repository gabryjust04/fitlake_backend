package com.fitlake.daily.infrastructure.persistence.mapper

import com.fitlake.daily.application.capture.DailyCapturePayloadCodec
import com.fitlake.daily.domain.capture.DailyCapture
import com.fitlake.daily.domain.capture.DailyCaptureId
import com.fitlake.daily.domain.capture.MealDraft
import com.fitlake.daily.domain.capture.MealItemDraft
import com.fitlake.daily.domain.common.DailyDay
import com.fitlake.daily.domain.common.DailyDayId
import com.fitlake.daily.domain.metrics.DailyMetrics
import com.fitlake.daily.infrastructure.persistence.entity.DailyCaptureEntity
import com.fitlake.daily.infrastructure.persistence.entity.DailyDayEntity
import com.fitlake.daily.infrastructure.persistence.entity.DailyMetricsEntity
import com.fitlake.user.domain.UserId
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.util.UUID

@Component
class DailyPersistenceMapper {
	fun toDomain(entity: DailyDayEntity): DailyDay = DailyDay(
		dayId = DailyDayId(entity.dayId),
		userId = UserId(entity.userId),
		dayDate = entity.dayDate,
		status = entity.status,
		openedAt = entity.openedAt,
		confirmedAt = entity.confirmedAt,
		reopenedAt = entity.reopenedAt,
		createdAt = entity.createdAt,
		updatedAt = entity.updatedAt,
		version = entity.version,
	)

	fun toEntity(domain: DailyDay): DailyDayEntity = DailyDayEntity(
		dayId = domain.dayId.value,
		userId = domain.userId.value,
		dayDate = domain.dayDate,
		status = domain.status,
		openedAt = domain.openedAt,
		confirmedAt = domain.confirmedAt,
		reopenedAt = domain.reopenedAt,
		createdAt = domain.createdAt,
		updatedAt = domain.updatedAt,
		version = domain.version,
	)

	fun toDomain(entity: DailyCaptureEntity): DailyCapture = DailyCapture(
		captureId = DailyCaptureId(entity.captureId),
		userId = UserId(entity.userId),
		dayId = DailyDayId(entity.dayId),
		sourceEventId = entity.sourceEventId,
		captureType = entity.captureType,
		status = entity.status,
		payload = DailyCapturePayloadCodec.decode(entity.payload),
		confidence = entity.confidence,
		createdBy = entity.createdBy,
		updatedBy = entity.updatedBy,
		acceptedAt = entity.acceptedAt,
		rejectedAt = entity.rejectedAt,
		deletedAt = entity.deletedAt,
		expiredAt = entity.expiredAt,
		createdAt = entity.createdAt,
		updatedAt = entity.updatedAt,
		version = entity.version,
	)

	fun toEntity(domain: DailyCapture): DailyCaptureEntity = DailyCaptureEntity(
		captureId = domain.captureId.value,
		userId = domain.userId.value,
		dayId = domain.dayId.value,
		sourceEventId = domain.sourceEventId,
		captureType = domain.captureType,
		status = domain.status,
		payload = DailyCapturePayloadCodec.encode(domain.payload),
		confidence = domain.confidence,
		createdBy = domain.createdBy,
		updatedBy = domain.updatedBy,
		acceptedAt = domain.acceptedAt,
		rejectedAt = domain.rejectedAt,
		deletedAt = domain.deletedAt,
		expiredAt = domain.expiredAt,
		createdAt = domain.createdAt,
		updatedAt = domain.updatedAt,
		version = domain.version,
	)

	fun toDomain(entity: DailyMetricsEntity): DailyMetrics = DailyMetrics(
		dayId = DailyDayId(entity.dayId),
		userId = UserId(entity.userId),
		dayDate = entity.dayDate,
		status = entity.status,
		bodyWeightKg = entity.bodyWeightKg,
		sleepHours = entity.sleepHours,
		stepsCount = entity.stepsCount,
		hydrationLiters = entity.hydrationLiters,
		caffeineMg = entity.caffeineMg,
		moodLevel = entity.moodLevel?.toInt(),
		focusLevel = entity.focusLevel?.toInt(),
		stressLevel = entity.stressLevel?.toInt(),
		totalCalories = entity.totalCalories,
		proteinG = entity.proteinG,
		carbsG = entity.carbsG,
		fatG = entity.fatG,
		foodLog = entity.foodLog.map { it.toMeal() },
		dailyNotes = entity.dailyNotes,
		experimentalData = entity.experimentalData,
		generatedFromCaptureIds = entity.generatedFromCaptureIds.map(UUID::fromString),
		confirmedAt = entity.confirmedAt,
		recalculatedAt = entity.recalculatedAt,
		createdAt = entity.createdAt,
		updatedAt = entity.updatedAt,
	)

	fun toEntity(domain: DailyMetrics): DailyMetricsEntity = DailyMetricsEntity(
		dayId = domain.dayId.value,
		userId = domain.userId.value,
		dayDate = domain.dayDate,
		status = domain.status,
		bodyWeightKg = domain.bodyWeightKg,
		sleepHours = domain.sleepHours,
		stepsCount = domain.stepsCount,
		hydrationLiters = domain.hydrationLiters,
		caffeineMg = domain.caffeineMg,
		moodLevel = domain.moodLevel?.toShort(),
		focusLevel = domain.focusLevel?.toShort(),
		stressLevel = domain.stressLevel?.toShort(),
		totalCalories = domain.totalCalories,
		proteinG = domain.proteinG,
		carbsG = domain.carbsG,
		fatG = domain.fatG,
		foodLog = domain.foodLog.map { it.toMap() },
		dailyNotes = domain.dailyNotes,
		experimentalData = domain.experimentalData,
		generatedFromCaptureIds = domain.generatedFromCaptureIds.map(UUID::toString),
		confirmedAt = domain.confirmedAt,
		recalculatedAt = domain.recalculatedAt,
		createdAt = domain.createdAt,
		updatedAt = domain.updatedAt,
	)

	private fun MealDraft.toMap(): Map<String, Any?> = linkedMapOf(
		"mealTempId" to mealTempId,
		"mealName" to mealName,
		"items" to items.map { it.toMap() },
	)

	private fun Map<String, Any?>.toMeal(): MealDraft = MealDraft(
		mealTempId = string("mealTempId"),
		mealName = nullableString("mealName"),
		items = listOfMaps("items").map { it.toMealItem() },
	)

	private fun MealItemDraft.toMap(): Map<String, Any?> = linkedMapOf(
		"itemTempId" to itemTempId,
		"foodName" to foodName,
		"quantity" to quantity,
		"unit" to unit,
		"calories" to calories,
		"proteinG" to proteinG,
		"carbsG" to carbsG,
		"fatG" to fatG,
	)

	private fun Map<String, Any?>.toMealItem(): MealItemDraft = MealItemDraft(
		itemTempId = string("itemTempId"),
		foodName = string("foodName"),
		quantity = decimal("quantity") ?: error("Missing food quantity"),
		unit = string("unit"),
		calories = decimal("calories"),
		proteinG = decimal("proteinG"),
		carbsG = decimal("carbsG"),
		fatG = decimal("fatG"),
	)

	private fun Map<String, Any?>.string(key: String): String =
		this[key] as? String ?: error("Missing or invalid JSON field: $key")

	private fun Map<String, Any?>.nullableString(key: String): String? = this[key] as? String

	private fun Map<String, Any?>.decimal(key: String): BigDecimal? = when (val value = this[key]) {
		null -> null
		is BigDecimal -> value
		is Number -> value.toString().toBigDecimal()
		is String -> value.toBigDecimal()
		else -> error("Invalid numeric JSON field: $key")
	}

	@Suppress("UNCHECKED_CAST")
	private fun Map<String, Any?>.listOfMaps(key: String): List<Map<String, Any?>> =
		this[key] as? List<Map<String, Any?>> ?: emptyList()
}
