package com.fitlake.daily.domain.metrics

import com.fitlake.daily.domain.capture.MealDraft
import com.fitlake.daily.domain.common.DailyDayId
import com.fitlake.daily.domain.common.DailyDayStatus
import com.fitlake.user.domain.UserId
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

data class DailyMetrics(
	val dayId: DailyDayId,
	val userId: UserId,
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
	val foodLog: List<MealDraft>,
	val dailyNotes: String?,
	val experimentalData: Map<String, Any?>,
	val generatedFromCaptureIds: List<UUID>,
	val confirmedAt: Instant?,
	val recalculatedAt: Instant?,
	val createdAt: Instant,
	val updatedAt: Instant,
)
