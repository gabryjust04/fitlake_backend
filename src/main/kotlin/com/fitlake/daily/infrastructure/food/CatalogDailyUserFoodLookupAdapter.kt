package com.fitlake.daily.infrastructure.food

import com.fitlake.daily.application.port.DailyOwnedUserFood
import com.fitlake.daily.application.port.DailyUserFoodLookupPort
import com.fitlake.daily.domain.capture.DailyFoodBasisSnapshot
import com.fitlake.daily.domain.capture.DailyFoodConversionSnapshot
import com.fitlake.daily.domain.capture.DailyFoodDefaultServingSnapshot
import com.fitlake.daily.domain.capture.DailyFoodItemSourceType
import com.fitlake.daily.domain.capture.DailyFoodSnapshotUnit
import com.fitlake.daily.domain.capture.DailyNutritionSourceSnapshot
import com.fitlake.daily.domain.capture.DailyNutritionValues
import com.fitlake.food.application.port.UserFoodRepository
import com.fitlake.food.domain.FoodUnit
import com.fitlake.food.domain.UserFood
import com.fitlake.food.domain.UserFoodId
import com.fitlake.user.domain.UserId
import org.springframework.stereotype.Component
import java.util.UUID

@Component
class CatalogDailyUserFoodLookupAdapter(
	private val repository: UserFoodRepository,
) : DailyUserFoodLookupPort {
	override fun findActiveOwnedFood(userId: UserId, userFoodId: UUID): DailyOwnedUserFood? =
		repository.findActiveByIdAndUserId(UserFoodId(userFoodId), userId)?.toDailyOwnedUserFood()
}

internal fun UserFood.toDailyOwnedUserFood() = DailyOwnedUserFood(
		userFoodId = foodId.value,
		displayName = name,
		brand = brand,
		nutritionBasis = DailyFoodBasisSnapshot(nutritionBasis.amount, nutritionBasis.unit.toDailyUnit()),
		nutrientsPerBasis = DailyNutritionValues(
			caloriesKcal = nutrients.caloriesKcal,
			proteinGrams = nutrients.proteinGrams,
			carbohydratesGrams = nutrients.carbohydratesGrams,
			fatGrams = nutrients.fatGrams,
			fiberGrams = nutrients.fiberGrams,
			sugarsGrams = nutrients.sugarsGrams,
			saturatedFatGrams = nutrients.saturatedFatGrams,
			sodiumMilligrams = nutrients.sodiumMilligrams,
			saltGrams = nutrients.saltGrams,
		),
		defaultServing = defaultServing?.let { DailyFoodDefaultServingSnapshot(it.amount, it.unit.toDailyUnit()) },
		conversions = DailyFoodConversionSnapshot(
			gramsPerPiece = conversions.gramsPerPiece,
			millilitersPerPiece = conversions.millilitersPerPiece,
			gramsPerServing = conversions.gramsPerServing,
			millilitersPerServing = conversions.millilitersPerServing,
		),
		nutritionSource = DailyNutritionSourceSnapshot(
			type = DailyFoodItemSourceType.USER_FOOD,
			originalSourceType = source.type.name,
			estimated = source.estimated,
			provider = source.provider,
			externalId = source.externalId,
			notes = source.notes,
			copiedAt = source.copiedAt,
		),
		version = version,
		updatedAt = updatedAt,
	)

private fun FoodUnit.toDailyUnit(): DailyFoodSnapshotUnit = when (this) {
	FoodUnit.GRAM -> DailyFoodSnapshotUnit.GRAM
	FoodUnit.KILOGRAM -> DailyFoodSnapshotUnit.KILOGRAM
	FoodUnit.MILLILITER -> DailyFoodSnapshotUnit.MILLILITER
	FoodUnit.LITER -> DailyFoodSnapshotUnit.LITER
	FoodUnit.PIECE -> DailyFoodSnapshotUnit.PIECE
	FoodUnit.SERVING -> DailyFoodSnapshotUnit.SERVING
}
