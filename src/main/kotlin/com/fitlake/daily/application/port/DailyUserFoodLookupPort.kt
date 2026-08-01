package com.fitlake.daily.application.port

import com.fitlake.daily.domain.capture.DailyFoodBasisSnapshot
import com.fitlake.daily.domain.capture.DailyFoodConversionSnapshot
import com.fitlake.daily.domain.capture.DailyFoodDefaultServingSnapshot
import com.fitlake.daily.domain.capture.DailyNutritionSourceSnapshot
import com.fitlake.daily.domain.capture.DailyNutritionValues
import com.fitlake.daily.domain.capture.DailyUserFoodSnapshot
import com.fitlake.user.domain.UserId
import java.time.Instant
import java.util.UUID

data class DailyOwnedUserFood(
	val userFoodId: UUID,
	val displayName: String,
	val brand: String?,
	val nutritionBasis: DailyFoodBasisSnapshot,
	val nutrientsPerBasis: DailyNutritionValues,
	val defaultServing: DailyFoodDefaultServingSnapshot?,
	val conversions: DailyFoodConversionSnapshot,
	val nutritionSource: DailyNutritionSourceSnapshot,
	val version: Long,
	val updatedAt: Instant,
) {
	fun snapshot(): DailyUserFoodSnapshot = DailyUserFoodSnapshot(
		nutritionBasis = nutritionBasis,
		nutrientsPerBasis = nutrientsPerBasis,
		defaultServing = defaultServing,
		conversions = conversions,
		nutritionSource = nutritionSource,
		userFoodVersion = version,
		userFoodUpdatedAt = updatedAt,
	)
}

interface DailyUserFoodLookupPort {
	fun findActiveOwnedFood(userId: UserId, userFoodId: UUID): DailyOwnedUserFood?
}

