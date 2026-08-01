package com.fitlake.daily.infrastructure.persistence.entity

import com.fitlake.daily.domain.common.DailyDayStatus
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

@Entity
@Table(name = "daily_metrics")
class DailyMetricsEntity(
	@Id
	@Column(name = "day_id", nullable = false, updatable = false)
	var dayId: UUID,

	@Column(name = "user_id", nullable = false, updatable = false)
	var userId: UUID,

	@Column(name = "day_date", nullable = false, updatable = false)
	var dayDate: LocalDate,

	@Enumerated(EnumType.STRING)
	@Column(name = "status", nullable = false, length = 20)
	var status: DailyDayStatus,

	@Column(name = "body_weight_kg") var bodyWeightKg: BigDecimal?,
	@Column(name = "sleep_hours") var sleepHours: BigDecimal?,
	@Column(name = "steps_count") var stepsCount: Int?,
	@Column(name = "hydration_liters") var hydrationLiters: BigDecimal?,
	@Column(name = "caffeine_mg") var caffeineMg: Int?,
	@Column(name = "mood_level") var moodLevel: Short?,
	@Column(name = "focus_level") var focusLevel: Short?,
	@Column(name = "stress_level") var stressLevel: Short?,
	@Column(name = "total_calories", precision = 18, scale = 6) var totalCalories: BigDecimal?,
	@Column(name = "protein_g") var proteinG: BigDecimal?,
	@Column(name = "carbs_g") var carbsG: BigDecimal?,
	@Column(name = "fat_g") var fatG: BigDecimal?,

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(name = "food_log", nullable = false, columnDefinition = "jsonb")
	var foodLog: List<Map<String, Any?>>,

	@Column(name = "daily_notes") var dailyNotes: String?,

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(name = "experimental_data", nullable = false, columnDefinition = "jsonb")
	var experimentalData: Map<String, Any?>,

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(name = "generated_from_capture_ids", nullable = false, columnDefinition = "jsonb")
	var generatedFromCaptureIds: List<String>,

	@Column(name = "confirmed_at") var confirmedAt: Instant?,
	@Column(name = "recalculated_at") var recalculatedAt: Instant?,
	@Column(name = "created_at", nullable = false, updatable = false) var createdAt: Instant,
	@Column(name = "updated_at", nullable = false) var updatedAt: Instant,
)
