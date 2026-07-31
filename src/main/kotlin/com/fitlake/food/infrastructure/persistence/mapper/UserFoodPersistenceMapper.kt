package com.fitlake.food.infrastructure.persistence.mapper

import com.fitlake.food.domain.DefaultServing
import com.fitlake.food.domain.NutritionBasis
import com.fitlake.food.domain.NutritionSource
import com.fitlake.food.domain.NutrientValues
import com.fitlake.food.domain.UnitConversions
import com.fitlake.food.domain.UserFood
import com.fitlake.food.domain.UserFoodAlias
import com.fitlake.food.domain.UserFoodId
import com.fitlake.food.infrastructure.persistence.entity.UserFoodAliasEntity
import com.fitlake.food.infrastructure.persistence.entity.UserFoodEntity
import com.fitlake.user.domain.UserId
import org.springframework.stereotype.Component
import java.math.BigDecimal

@Component
class UserFoodPersistenceMapper {
	fun toDomain(entity: UserFoodEntity, aliases: List<UserFoodAlias>): UserFood = UserFood(
		foodId = UserFoodId(entity.userFoodId),
		userId = UserId(entity.userId),
		name = entity.name,
		normalizedName = entity.normalizedName,
		brand = entity.brand,
		barcode = entity.barcode,
		description = entity.description,
		aliases = aliases.sortedBy(UserFoodAlias::normalizedValue),
		nutritionBasis = NutritionBasis(entity.basisAmount.normalizedDecimal(), entity.basisUnit),
		nutrients = NutrientValues(
			caloriesKcal = entity.caloriesKcal.normalizedNullableDecimal(),
			proteinGrams = entity.proteinGrams.normalizedNullableDecimal(),
			carbohydratesGrams = entity.carbohydratesGrams.normalizedNullableDecimal(),
			fatGrams = entity.fatGrams.normalizedNullableDecimal(),
			fiberGrams = entity.fiberGrams.normalizedNullableDecimal(),
			sugarsGrams = entity.sugarsGrams.normalizedNullableDecimal(),
			saturatedFatGrams = entity.saturatedFatGrams.normalizedNullableDecimal(),
			sodiumMilligrams = entity.sodiumMilligrams.normalizedNullableDecimal(),
			saltGrams = entity.saltGrams.normalizedNullableDecimal(),
		),
		defaultServing = entity.defaultServingAmount?.let { amount ->
			DefaultServing(amount.normalizedDecimal(), requireNotNull(entity.defaultServingUnit))
		},
		conversions = UnitConversions(
			gramsPerPiece = entity.gramsPerPiece.normalizedNullableDecimal(),
			millilitersPerPiece = entity.millilitersPerPiece.normalizedNullableDecimal(),
			gramsPerServing = entity.gramsPerServing.normalizedNullableDecimal(),
			millilitersPerServing = entity.millilitersPerServing.normalizedNullableDecimal(),
		),
		source = NutritionSource(
			type = entity.sourceType,
			provider = entity.sourceProvider,
			externalId = entity.sourceExternalId,
			notes = entity.sourceNotes,
			copiedAt = entity.sourceCopiedAt,
		),
		createdAt = entity.createdAt,
		updatedAt = entity.updatedAt,
		deletedAt = entity.deletedAt,
		version = entity.version,
	)

	fun aliasToDomain(entity: UserFoodAliasEntity): UserFoodAlias = UserFoodAlias(
		aliasId = entity.aliasId,
		value = entity.alias,
		normalizedValue = entity.normalizedAlias,
	)

	fun toEntity(food: UserFood): UserFoodEntity = UserFoodEntity(
		userFoodId = food.foodId.value,
		userId = food.userId.value,
		name = food.name,
		normalizedName = food.normalizedName,
		brand = food.brand,
		barcode = food.barcode,
		description = food.description,
		basisAmount = food.nutritionBasis.amount,
		basisUnit = food.nutritionBasis.unit,
		caloriesKcal = food.nutrients.caloriesKcal,
		proteinGrams = food.nutrients.proteinGrams,
		carbohydratesGrams = food.nutrients.carbohydratesGrams,
		fatGrams = food.nutrients.fatGrams,
		fiberGrams = food.nutrients.fiberGrams,
		sugarsGrams = food.nutrients.sugarsGrams,
		saturatedFatGrams = food.nutrients.saturatedFatGrams,
		sodiumMilligrams = food.nutrients.sodiumMilligrams,
		saltGrams = food.nutrients.saltGrams,
		defaultServingAmount = food.defaultServing?.amount,
		defaultServingUnit = food.defaultServing?.unit,
		gramsPerPiece = food.conversions.gramsPerPiece,
		millilitersPerPiece = food.conversions.millilitersPerPiece,
		gramsPerServing = food.conversions.gramsPerServing,
		millilitersPerServing = food.conversions.millilitersPerServing,
		sourceType = food.source.type,
		sourceProvider = food.source.provider,
		sourceExternalId = food.source.externalId,
		sourceNotes = food.source.notes,
		sourceCopiedAt = food.source.copiedAt,
		createdAt = food.createdAt,
		updatedAt = food.updatedAt,
		deletedAt = food.deletedAt,
		version = food.version,
	)

	fun updateEntity(food: UserFood, entity: UserFoodEntity) {
		entity.name = food.name
		entity.normalizedName = food.normalizedName
		entity.brand = food.brand
		entity.barcode = food.barcode
		entity.description = food.description
		entity.basisAmount = food.nutritionBasis.amount
		entity.basisUnit = food.nutritionBasis.unit
		entity.caloriesKcal = food.nutrients.caloriesKcal
		entity.proteinGrams = food.nutrients.proteinGrams
		entity.carbohydratesGrams = food.nutrients.carbohydratesGrams
		entity.fatGrams = food.nutrients.fatGrams
		entity.fiberGrams = food.nutrients.fiberGrams
		entity.sugarsGrams = food.nutrients.sugarsGrams
		entity.saturatedFatGrams = food.nutrients.saturatedFatGrams
		entity.sodiumMilligrams = food.nutrients.sodiumMilligrams
		entity.saltGrams = food.nutrients.saltGrams
		entity.defaultServingAmount = food.defaultServing?.amount
		entity.defaultServingUnit = food.defaultServing?.unit
		entity.gramsPerPiece = food.conversions.gramsPerPiece
		entity.millilitersPerPiece = food.conversions.millilitersPerPiece
		entity.gramsPerServing = food.conversions.gramsPerServing
		entity.millilitersPerServing = food.conversions.millilitersPerServing
		entity.sourceType = food.source.type
		entity.sourceProvider = food.source.provider
		entity.sourceExternalId = food.source.externalId
		entity.sourceNotes = food.source.notes
		entity.sourceCopiedAt = food.source.copiedAt
		entity.updatedAt = food.updatedAt
		entity.deletedAt = food.deletedAt
	}

	fun toAliasEntity(food: UserFood, alias: UserFoodAlias): UserFoodAliasEntity = UserFoodAliasEntity(
		aliasId = alias.aliasId,
		userFoodId = food.foodId.value,
		userId = food.userId.value,
		alias = alias.value,
		normalizedAlias = alias.normalizedValue,
		createdAt = food.updatedAt,
		deletedAt = food.deletedAt,
	)
}

private fun BigDecimal.normalizedDecimal(): BigDecimal = stripTrailingZeros().let { normalized ->
	if (normalized.scale() < 0) normalized.setScale(0) else normalized
}

private fun BigDecimal?.normalizedNullableDecimal(): BigDecimal? = this?.normalizedDecimal()
