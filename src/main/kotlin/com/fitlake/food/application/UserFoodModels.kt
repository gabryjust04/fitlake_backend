package com.fitlake.food.application

import com.fitlake.food.domain.FoodUnit
import com.fitlake.food.domain.NutritionBasis
import com.fitlake.food.domain.NutritionSource
import com.fitlake.food.domain.NutritionSourceType
import com.fitlake.food.domain.NutrientValues
import com.fitlake.food.domain.DefaultServing
import com.fitlake.food.domain.UnitConversions
import com.fitlake.food.domain.UserFood
import com.fitlake.food.domain.UserFoodDefinition
import com.fitlake.food.domain.UserFoodId
import com.fitlake.food.domain.UserFoodTextNormalizer
import com.fitlake.user.domain.UserId
import java.math.BigDecimal
import java.time.LocalDate

data class NutritionBasisInput(
	val amount: BigDecimal,
	val unit: FoodUnit,
)

data class NutrientValuesInput(
	val caloriesKcal: BigDecimal? = null,
	val proteinGrams: BigDecimal? = null,
	val carbohydratesGrams: BigDecimal? = null,
	val fatGrams: BigDecimal? = null,
	val fiberGrams: BigDecimal? = null,
	val sugarsGrams: BigDecimal? = null,
	val saturatedFatGrams: BigDecimal? = null,
	val sodiumMilligrams: BigDecimal? = null,
	val saltGrams: BigDecimal? = null,
)

data class DefaultServingInput(
	val amount: BigDecimal,
	val unit: FoodUnit,
)

data class UnitConversionsInput(
	val gramsPerPiece: BigDecimal? = null,
	val millilitersPerPiece: BigDecimal? = null,
	val gramsPerServing: BigDecimal? = null,
	val millilitersPerServing: BigDecimal? = null,
)

data class NutritionSourceInput(
	val type: NutritionSourceType,
	val provider: String? = null,
	val externalId: String? = null,
	val notes: String? = null,
	val copiedAt: LocalDate? = null,
)

data class UserFoodDefinitionInput(
	val name: String,
	val brand: String? = null,
	val barcode: String? = null,
	val description: String? = null,
	val aliases: List<String> = emptyList(),
	val nutritionBasis: NutritionBasisInput,
	val nutrients: NutrientValuesInput,
	val defaultServing: DefaultServingInput? = null,
	val conversions: UnitConversionsInput = UnitConversionsInput(),
	val source: NutritionSourceInput,
) {
	fun toDefinition(): UserFoodDefinition = UserFoodDefinition.from(
		name = name,
		brand = brand,
		barcode = barcode,
		description = description,
		aliases = aliases,
		nutritionBasis = NutritionBasis(nutritionBasis.amount, nutritionBasis.unit),
		nutrients = NutrientValues(
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
		defaultServing = defaultServing?.let { DefaultServing(it.amount, it.unit) },
		conversions = UnitConversions(
			gramsPerPiece = conversions.gramsPerPiece,
			millilitersPerPiece = conversions.millilitersPerPiece,
			gramsPerServing = conversions.gramsPerServing,
			millilitersPerServing = conversions.millilitersPerServing,
		),
		source = NutritionSource(
			type = source.type,
			provider = source.provider,
			externalId = source.externalId,
			notes = source.notes,
			copiedAt = source.copiedAt,
		),
	)
}

enum class UserFoodSort {
	NAME_ASC,
	CREATED_AT_DESC,
	UPDATED_AT_DESC,
}

data class UserFoodPageQuery(
	val page: Int = 0,
	val size: Int = 20,
	val sort: UserFoodSort = UserFoodSort.NAME_ASC,
)

data class UserFoodPage(
	val items: List<UserFood>,
	val page: Int,
	val size: Int,
	val totalElements: Long,
) {
	val totalPages: Int = if (totalElements == 0L) 0 else ((totalElements - 1) / size + 1).toInt()
}

enum class UserFoodMatchType {
	EXACT_BARCODE,
	EXACT_ALIAS,
	EXACT_NAME,
	PREFIX_ALIAS,
	PREFIX_NAME,
	FUZZY_ALIAS,
	FUZZY_NAME,
}

data class UserFoodCandidate(
	val foodId: UserFoodId,
	val name: String,
	val brand: String?,
	val matchedBy: UserFoodMatchType,
	val matchedText: String,
	val score: Double,
	val nutritionBasis: NutritionBasis,
	val defaultServing: DefaultServing?,
	val sourceType: NutritionSourceType,
)

data class UserFoodSearchQuery(
	val normalizedQuery: String,
	val barcode: String?,
	val limit: Int,
	val fuzzyEnabled: Boolean,
)

interface SearchUserFoodsUseCase {
	fun search(userId: UserId, query: String, limit: Int = 10): List<UserFoodCandidate>

	/** Internal AI lookup variant; implementations may lower technical-log verbosity. */
	fun searchForDailyAi(userId: UserId, query: String, limit: Int): List<UserFoodCandidate> =
		search(userId, query, limit)
}

internal fun normalizeSearchQuery(query: String): String = UserFoodTextNormalizer.normalize(query)
