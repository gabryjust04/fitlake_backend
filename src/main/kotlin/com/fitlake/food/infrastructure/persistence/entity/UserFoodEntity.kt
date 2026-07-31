package com.fitlake.food.infrastructure.persistence.entity

import com.fitlake.food.domain.FoodUnit
import com.fitlake.food.domain.NutritionSourceType
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.Version
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

@Entity
@Table(name = "user_food")
class UserFoodEntity(
	@Id
	@Column(name = "user_food_id", nullable = false, updatable = false)
	var userFoodId: UUID,

	@Column(name = "user_id", nullable = false, updatable = false)
	var userId: UUID,

	@Column(name = "name", nullable = false, length = 160)
	var name: String,

	@Column(name = "normalized_name", nullable = false, length = 160)
	var normalizedName: String,

	@Column(name = "brand", length = 120)
	var brand: String?,

	@Column(name = "barcode", length = 14)
	var barcode: String?,

	@Column(name = "description", length = 1000)
	var description: String?,

	@Column(name = "basis_amount", nullable = false, precision = 18, scale = 6)
	var basisAmount: BigDecimal,

	@Enumerated(EnumType.STRING)
	@Column(name = "basis_unit", nullable = false, length = 20)
	var basisUnit: FoodUnit,

	@Column(name = "calories_kcal", precision = 18, scale = 6)
	var caloriesKcal: BigDecimal?,

	@Column(name = "protein_grams", precision = 18, scale = 6)
	var proteinGrams: BigDecimal?,

	@Column(name = "carbohydrates_grams", precision = 18, scale = 6)
	var carbohydratesGrams: BigDecimal?,

	@Column(name = "fat_grams", precision = 18, scale = 6)
	var fatGrams: BigDecimal?,

	@Column(name = "fiber_grams", precision = 18, scale = 6)
	var fiberGrams: BigDecimal?,

	@Column(name = "sugars_grams", precision = 18, scale = 6)
	var sugarsGrams: BigDecimal?,

	@Column(name = "saturated_fat_grams", precision = 18, scale = 6)
	var saturatedFatGrams: BigDecimal?,

	@Column(name = "sodium_milligrams", precision = 18, scale = 6)
	var sodiumMilligrams: BigDecimal?,

	@Column(name = "salt_grams", precision = 18, scale = 6)
	var saltGrams: BigDecimal?,

	@Column(name = "default_serving_amount", precision = 18, scale = 6)
	var defaultServingAmount: BigDecimal?,

	@Enumerated(EnumType.STRING)
	@Column(name = "default_serving_unit", length = 20)
	var defaultServingUnit: FoodUnit?,

	@Column(name = "grams_per_piece", precision = 18, scale = 6)
	var gramsPerPiece: BigDecimal?,

	@Column(name = "milliliters_per_piece", precision = 18, scale = 6)
	var millilitersPerPiece: BigDecimal?,

	@Column(name = "grams_per_serving", precision = 18, scale = 6)
	var gramsPerServing: BigDecimal?,

	@Column(name = "milliliters_per_serving", precision = 18, scale = 6)
	var millilitersPerServing: BigDecimal?,

	@Enumerated(EnumType.STRING)
	@Column(name = "source_type", nullable = false, length = 40)
	var sourceType: NutritionSourceType,

	@Column(name = "source_provider", length = 120)
	var sourceProvider: String?,

	@Column(name = "source_external_id", length = 255)
	var sourceExternalId: String?,

	@Column(name = "source_notes", length = 1000)
	var sourceNotes: String?,

	@Column(name = "source_copied_at")
	var sourceCopiedAt: LocalDate?,

	@Column(name = "created_at", nullable = false, updatable = false)
	var createdAt: Instant,

	@Column(name = "updated_at", nullable = false)
	var updatedAt: Instant,

	@Column(name = "deleted_at")
	var deletedAt: Instant?,

	@Version
	@Column(name = "version", nullable = false)
	var version: Long,
)
