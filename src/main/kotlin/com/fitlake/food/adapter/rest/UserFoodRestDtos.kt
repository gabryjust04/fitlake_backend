package com.fitlake.food.adapter.rest

import com.fitlake.food.application.DefaultServingInput
import com.fitlake.food.application.NutrientValuesInput
import com.fitlake.food.application.NutritionBasisInput
import com.fitlake.food.application.NutritionSourceInput
import com.fitlake.food.application.UnitConversionsInput
import com.fitlake.food.application.UserFoodCandidate
import com.fitlake.food.application.UserFoodDefinitionInput
import com.fitlake.food.application.UserFoodPage
import com.fitlake.food.application.UserFoodMatchType
import com.fitlake.food.domain.DefaultServing
import com.fitlake.food.domain.FoodUnit
import com.fitlake.food.domain.NutritionBasis
import com.fitlake.food.domain.NutritionSourceType
import com.fitlake.food.domain.NutrientValues
import com.fitlake.food.domain.UnitConversions
import com.fitlake.food.domain.UserFood
import com.fitlake.food.domain.UserFoodLimits
import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.Constraint
import jakarta.validation.Payload
import jakarta.validation.Valid
import jakarta.validation.constraints.DecimalMin
import jakarta.validation.constraints.Digits
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import kotlin.reflect.KClass

data class UserFoodDefinitionRequest(
	@field:NotBlank
	@field:Size(max = UserFoodLimits.NAME_MAX_LENGTH)
	@field:Schema(example = "My usual Greek yogurt")
	val name: String,
	@field:Size(max = UserFoodLimits.BRAND_MAX_LENGTH)
	@field:Schema(example = "Example Brand")
	val brand: String? = null,
	@field:Size(max = 14)
	@field:Schema(example = "1234567890123")
	val barcode: String? = null,
	@field:Size(max = UserFoodLimits.DESCRIPTION_MAX_LENGTH)
	val description: String? = null,
	@field:Size(max = UserFoodLimits.ALIAS_MAX_COUNT)
	val aliases: List<String> = emptyList(),
	@field:Valid
	val nutritionBasis: NutritionBasisRequest,
	@field:Valid
	val nutrients: NutrientValuesRequest,
	@field:Valid
	val defaultServing: DefaultServingRequest? = null,
	@field:Valid
	val conversions: UnitConversionsRequest = UnitConversionsRequest(),
	@field:Valid
	val source: NutritionSourceRequest,
) {
	fun toInput(): UserFoodDefinitionInput = UserFoodDefinitionInput(
		name = name,
		brand = brand,
		barcode = barcode,
		description = description,
		aliases = aliases,
		nutritionBasis = nutritionBasis.toInput(),
		nutrients = nutrients.toInput(),
		defaultServing = defaultServing?.toInput(),
		conversions = conversions.toInput(),
		source = source.toInput(),
	)
}

data class NutritionBasisRequest(
	@field:DecimalMin("0.000001")
	@field:Digits(integer = 12, fraction = 6)
	val amount: BigDecimal,
	val unit: FoodUnit,
) {
	fun toInput() = NutritionBasisInput(amount, unit)
}

data class NutrientValuesRequest(
	@field:NonNegativeDecimal val caloriesKcal: BigDecimal? = null,
	@field:NonNegativeDecimal val proteinGrams: BigDecimal? = null,
	@field:NonNegativeDecimal val carbohydratesGrams: BigDecimal? = null,
	@field:NonNegativeDecimal val fatGrams: BigDecimal? = null,
	@field:NonNegativeDecimal val fiberGrams: BigDecimal? = null,
	@field:NonNegativeDecimal val sugarsGrams: BigDecimal? = null,
	@field:NonNegativeDecimal val saturatedFatGrams: BigDecimal? = null,
	@field:NonNegativeDecimal val sodiumMilligrams: BigDecimal? = null,
	@field:NonNegativeDecimal val saltGrams: BigDecimal? = null,
) {
	fun toInput() = NutrientValuesInput(
		caloriesKcal,
		proteinGrams,
		carbohydratesGrams,
		fatGrams,
		fiberGrams,
		sugarsGrams,
		saturatedFatGrams,
		sodiumMilligrams,
		saltGrams,
	)
}

data class DefaultServingRequest(
	@field:DecimalMin("0.000001")
	@field:Digits(integer = 12, fraction = 6)
	val amount: BigDecimal,
	val unit: FoodUnit,
) {
	fun toInput() = DefaultServingInput(amount, unit)
}

data class UnitConversionsRequest(
	@field:PositiveDecimal val gramsPerPiece: BigDecimal? = null,
	@field:PositiveDecimal val millilitersPerPiece: BigDecimal? = null,
	@field:PositiveDecimal val gramsPerServing: BigDecimal? = null,
	@field:PositiveDecimal val millilitersPerServing: BigDecimal? = null,
) {
	fun toInput() = UnitConversionsInput(
		gramsPerPiece,
		millilitersPerPiece,
		gramsPerServing,
		millilitersPerServing,
	)
}

data class NutritionSourceRequest(
	val type: NutritionSourceType,
	@field:Size(max = UserFoodLimits.SOURCE_PROVIDER_MAX_LENGTH)
	val provider: String? = null,
	@field:Size(max = UserFoodLimits.SOURCE_EXTERNAL_ID_MAX_LENGTH)
	val externalId: String? = null,
	@field:Size(max = UserFoodLimits.SOURCE_NOTES_MAX_LENGTH)
	val notes: String? = null,
	val copiedAt: LocalDate? = null,
) {
	fun toInput() = NutritionSourceInput(type, provider, externalId, notes, copiedAt)
}

data class NutritionBasisResponse(val amount: BigDecimal, val unit: FoodUnit)

data class NutrientValuesResponse(
	@field:Schema(description = "Missing nutrient values are unknown, never implicit zero.")
	val caloriesKcal: BigDecimal?,
	val proteinGrams: BigDecimal?,
	val carbohydratesGrams: BigDecimal?,
	val fatGrams: BigDecimal?,
	val fiberGrams: BigDecimal?,
	val sugarsGrams: BigDecimal?,
	val saturatedFatGrams: BigDecimal?,
	val sodiumMilligrams: BigDecimal?,
	val saltGrams: BigDecimal?,
)

data class DefaultServingResponse(val amount: BigDecimal, val unit: FoodUnit)

data class UnitConversionsResponse(
	val gramsPerPiece: BigDecimal?,
	val millilitersPerPiece: BigDecimal?,
	val gramsPerServing: BigDecimal?,
	val millilitersPerServing: BigDecimal?,
)

data class NutritionSourceResponse(
	val type: NutritionSourceType,
	val provider: String?,
	val externalId: String?,
	val notes: String?,
	val copiedAt: LocalDate?,
	val estimated: Boolean,
)

data class UserFoodResponse(
	val foodId: UUID,
	val name: String,
	val brand: String?,
	val barcode: String?,
	val description: String?,
	val aliases: List<String>,
	val nutritionBasis: NutritionBasisResponse,
	val nutrients: NutrientValuesResponse,
	val defaultServing: DefaultServingResponse?,
	val conversions: UnitConversionsResponse,
	val source: NutritionSourceResponse,
	val createdAt: Instant,
	val updatedAt: Instant,
)

data class UserFoodPageResponse(
	val items: List<UserFoodResponse>,
	val page: Int,
	val size: Int,
	val totalElements: Long,
	val totalPages: Int,
)

data class UserFoodSearchCandidateResponse(
	val foodId: UUID,
	val name: String,
	val brand: String?,
	val matchedBy: UserFoodMatchType,
	val matchedText: String,
	val score: Double,
	val nutritionBasis: NutritionBasisResponse,
	val defaultServing: DefaultServingResponse?,
	val sourceType: NutritionSourceType,
)

data class UserFoodSearchResponse(
	val query: String,
	val results: List<UserFoodSearchCandidateResponse>,
)

fun UserFood.toResponse(): UserFoodResponse = UserFoodResponse(
	foodId = foodId.value,
	name = name,
	brand = brand,
	barcode = barcode,
	description = description,
	aliases = aliases.map { it.value },
	nutritionBasis = nutritionBasis.toResponse(),
	nutrients = nutrients.toResponse(),
	defaultServing = defaultServing?.toResponse(),
	conversions = conversions.toResponse(),
	source = NutritionSourceResponse(
		type = source.type,
		provider = source.provider,
		externalId = source.externalId,
		notes = source.notes,
		copiedAt = source.copiedAt,
		estimated = source.estimated,
	),
	createdAt = createdAt,
	updatedAt = updatedAt,
)

fun UserFoodPage.toResponse() = UserFoodPageResponse(
	items = items.map(UserFood::toResponse),
	page = page,
	size = size,
	totalElements = totalElements,
	totalPages = totalPages,
)

fun UserFoodCandidate.toResponse() = UserFoodSearchCandidateResponse(
	foodId = foodId.value,
	name = name,
	brand = brand,
	matchedBy = matchedBy,
	matchedText = matchedText,
	score = score,
	nutritionBasis = nutritionBasis.toResponse(),
	defaultServing = defaultServing?.toResponse(),
	sourceType = sourceType,
)

private fun NutritionBasis.toResponse() = NutritionBasisResponse(amount, unit)
private fun DefaultServing.toResponse() = DefaultServingResponse(amount, unit)
private fun NutrientValues.toResponse() = NutrientValuesResponse(
	caloriesKcal,
	proteinGrams,
	carbohydratesGrams,
	fatGrams,
	fiberGrams,
	sugarsGrams,
	saturatedFatGrams,
	sodiumMilligrams,
	saltGrams,
)
private fun UnitConversions.toResponse() = UnitConversionsResponse(
	gramsPerPiece,
	millilitersPerPiece,
	gramsPerServing,
	millilitersPerServing,
)

@Target(AnnotationTarget.FIELD, AnnotationTarget.ANNOTATION_CLASS)
@Retention(AnnotationRetention.RUNTIME)
@Constraint(validatedBy = [])
@DecimalMin("0.0")
@Digits(integer = 12, fraction = 6)
private annotation class NonNegativeDecimal(
	val message: String = "must be non-negative with at most 6 decimal places",
	val groups: Array<KClass<*>> = [],
	val payload: Array<KClass<out Payload>> = [],
)

@Target(AnnotationTarget.FIELD, AnnotationTarget.ANNOTATION_CLASS)
@Retention(AnnotationRetention.RUNTIME)
@Constraint(validatedBy = [])
@DecimalMin("0.000001")
@Digits(integer = 12, fraction = 6)
private annotation class PositiveDecimal(
	val message: String = "must be positive with at most 6 decimal places",
	val groups: Array<KClass<*>> = [],
	val payload: Array<KClass<out Payload>> = [],
)
