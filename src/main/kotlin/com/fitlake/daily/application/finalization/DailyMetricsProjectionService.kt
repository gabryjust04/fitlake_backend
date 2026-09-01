package com.fitlake.daily.application.finalization

import com.fitlake.daily.domain.capture.DailyCapture
import com.fitlake.daily.domain.capture.DailyCaptureStatus
import com.fitlake.daily.domain.capture.MealItemDraft
import com.fitlake.daily.domain.common.DailyDay
import com.fitlake.daily.domain.common.DailyDayStatus
import com.fitlake.daily.domain.metrics.DailyMetrics
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.time.Instant

@Service
class DailyMetricsProjectionService {
	fun projectCurrent(
		day: DailyDay,
		captures: List<DailyCapture>,
	): DailyMetrics {
		check(day.status == DailyDayStatus.OPEN) { "Only an open day can expose current metrics" }
		val projectedAt = captures
			.maxOfOrNull(DailyCapture::updatedAt)
			?.let { maxOf(day.updatedAt, it) }
			?: day.updatedAt
		return project(day, captures, existing = null, at = projectedAt).copy(
			status = DailyDayStatus.OPEN,
			confirmedAt = null,
			recalculatedAt = null,
		)
	}

	fun project(
		day: DailyDay,
		captures: List<DailyCapture>,
		existing: DailyMetrics?,
		at: Instant,
	): DailyMetrics {
		val accepted = captures
			.filter { it.status == DailyCaptureStatus.ACCEPTED }
			.sortedWith(compareBy<DailyCapture> { it.createdAt }.thenBy { it.captureId.value })

		var bodyWeightKg: BigDecimal? = null
		var sleepHours: BigDecimal? = null
		var stepsCount: Int? = null
		var hydrationLiters: BigDecimal? = null
		var caffeineMg: Int? = null
		var moodLevel: Int? = null
		var focusLevel: Int? = null
		var stressLevel: Int? = null
		var dailyNotes: String? = null
		val foodLog = mutableListOf<com.fitlake.daily.domain.capture.MealDraft>()

		accepted.forEach { capture ->
			val fields = capture.payload.fields
			fields.bodyWeightKg?.let { bodyWeightKg = it }
			fields.sleepHours?.let { sleepHours = it }
			fields.stepsCount?.let { stepsCount = it }
			fields.hydrationLiters?.let { hydrationLiters = it }
			fields.caffeineMg?.let { caffeineMg = it }
			fields.moodLevel?.let { moodLevel = it }
			fields.focusLevel?.let { focusLevel = it }
			fields.stressLevel?.let { stressLevel = it }
			fields.dailyNotes?.let { dailyNotes = it }
			capture.payload.note?.let { dailyNotes = it }
			foodLog += capture.payload.meals
		}

		val foodItems = foodLog.flatMap { it.items }
		return DailyMetrics(
			dayId = day.dayId,
			userId = day.userId,
			dayDate = day.dayDate,
			status = DailyDayStatus.CONFIRMED,
			bodyWeightKg = bodyWeightKg,
			sleepHours = sleepHours,
			stepsCount = stepsCount,
			hydrationLiters = hydrationLiters,
			caffeineMg = caffeineMg,
			moodLevel = moodLevel,
			focusLevel = focusLevel,
			stressLevel = stressLevel,
			totalCalories = foodItems.sumOptionalDecimal(MealItemDraft::calories),
			proteinG = foodItems.sumOptionalDecimal(MealItemDraft::proteinG),
			carbsG = foodItems.sumOptionalDecimal(MealItemDraft::carbsG),
			fatG = foodItems.sumOptionalDecimal(MealItemDraft::fatG),
			foodLog = foodLog,
			dailyNotes = dailyNotes,
			experimentalData = emptyMap(),
			generatedFromCaptureIds = accepted.map { it.captureId.value },
			confirmedAt = at,
			recalculatedAt = existing?.let { at },
			createdAt = existing?.createdAt ?: at,
			updatedAt = at,
		)
	}

	private fun List<MealItemDraft>.sumOptionalDecimal(selector: (MealItemDraft) -> BigDecimal?): BigDecimal? {
		if (isEmpty()) return null
		val values = map(selector)
		if (values.any { it == null }) return null
		return values.filterNotNull().fold(BigDecimal.ZERO, BigDecimal::add)
	}
}
