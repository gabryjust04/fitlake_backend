package com.fitlake.food.domain

import com.fitlake.user.domain.UserId
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

@JvmInline
value class UserFoodId(val value: UUID)

enum class FoodUnit(internal val dimension: FoodUnitDimension) {
	GRAM(FoodUnitDimension.MASS),
	KILOGRAM(FoodUnitDimension.MASS),
	MILLILITER(FoodUnitDimension.VOLUME),
	LITER(FoodUnitDimension.VOLUME),
	PIECE(FoodUnitDimension.PIECE),
	SERVING(FoodUnitDimension.SERVING),
}

internal enum class FoodUnitDimension {
	MASS,
	VOLUME,
	PIECE,
	SERVING,
}

enum class NutritionSourceType {
	USER_ENTERED,
	PRODUCT_LABEL,
	EXTERNAL_DATABASE,
	AI_ESTIMATE,
	IMPORTED,
}

data class NutritionBasis(
	val amount: BigDecimal,
	val unit: FoodUnit,
) {
	init {
		requirePositiveAmount(amount, "Nutrition basis amount")
	}
}

data class NutrientValues(
	val caloriesKcal: BigDecimal? = null,
	val proteinGrams: BigDecimal? = null,
	val carbohydratesGrams: BigDecimal? = null,
	val fatGrams: BigDecimal? = null,
	val fiberGrams: BigDecimal? = null,
	val sugarsGrams: BigDecimal? = null,
	val saturatedFatGrams: BigDecimal? = null,
	val sodiumMilligrams: BigDecimal? = null,
	val saltGrams: BigDecimal? = null,
) {
	init {
		validateNutrient(caloriesKcal, "Calories")
		validateNutrient(proteinGrams, "Protein")
		validateNutrient(carbohydratesGrams, "Carbohydrates")
		validateNutrient(fatGrams, "Fat")
		validateNutrient(fiberGrams, "Fiber")
		validateNutrient(sugarsGrams, "Sugars")
		validateNutrient(saturatedFatGrams, "Saturated fat")
		validateNutrient(sodiumMilligrams, "Sodium")
		validateNutrient(saltGrams, "Salt")
	}
}

data class DefaultServing(
	val amount: BigDecimal,
	val unit: FoodUnit,
) {
	init {
		requirePositiveAmount(amount, "Default serving amount")
	}
}

data class UnitConversions(
	val gramsPerPiece: BigDecimal? = null,
	val millilitersPerPiece: BigDecimal? = null,
	val gramsPerServing: BigDecimal? = null,
	val millilitersPerServing: BigDecimal? = null,
) {
	init {
		validateConversion(gramsPerPiece, "Grams per piece")
		validateConversion(millilitersPerPiece, "Milliliters per piece")
		validateConversion(gramsPerServing, "Grams per serving")
		validateConversion(millilitersPerServing, "Milliliters per serving")
		require(gramsPerPiece == null || millilitersPerPiece == null) {
			"A piece cannot have both mass and volume conversion metadata"
		}
		require(gramsPerServing == null || millilitersPerServing == null) {
			"A serving cannot have both mass and volume conversion metadata"
		}
	}

	internal fun supports(from: FoodUnit, to: FoodUnit): Boolean {
		if (from.dimension == to.dimension) {
			return from.dimension == FoodUnitDimension.MASS ||
				from.dimension == FoodUnitDimension.VOLUME ||
				from == to
		}
		val dimensions = setOf(from.dimension, to.dimension)
		return when (dimensions) {
			setOf(FoodUnitDimension.MASS, FoodUnitDimension.PIECE) -> gramsPerPiece != null
			setOf(FoodUnitDimension.VOLUME, FoodUnitDimension.PIECE) -> millilitersPerPiece != null
			setOf(FoodUnitDimension.MASS, FoodUnitDimension.SERVING) -> gramsPerServing != null
			setOf(FoodUnitDimension.VOLUME, FoodUnitDimension.SERVING) -> millilitersPerServing != null
			else -> false
		}
	}

	companion object {
		val NONE = UnitConversions()
	}
}

data class NutritionSource(
	val type: NutritionSourceType,
	val provider: String? = null,
	val externalId: String? = null,
	val notes: String? = null,
	val copiedAt: LocalDate? = null,
) {
	val estimated: Boolean
		get() = type == NutritionSourceType.AI_ESTIMATE

	init {
		require(provider == null || provider.isNotBlank()) { "Source provider must be null or non-blank" }
		require(provider == null || provider.length <= UserFoodLimits.SOURCE_PROVIDER_MAX_LENGTH) {
			"Source provider is too long"
		}
		require(externalId == null || externalId.isNotBlank()) { "Source external ID must be null or non-blank" }
		require(externalId == null || externalId.length <= UserFoodLimits.SOURCE_EXTERNAL_ID_MAX_LENGTH) {
			"Source external ID is too long"
		}
		require(notes == null || notes.isNotBlank()) { "Source notes must be null or non-blank" }
		require(notes == null || notes.length <= UserFoodLimits.SOURCE_NOTES_MAX_LENGTH) {
			"Source notes are too long"
		}
		require(externalId == null || provider != null) {
			"Source external ID requires a source provider"
		}
		if (type == NutritionSourceType.EXTERNAL_DATABASE) {
			require(provider != null && externalId != null) {
				"External database nutrition requires provider and external ID"
			}
		}
	}
}

data class FoodAliasValue(
	val value: String,
	val normalizedValue: String,
) {
	init {
		require(value.isNotBlank()) { "Food alias must not be blank" }
		require(value.length <= UserFoodLimits.ALIAS_MAX_LENGTH) { "Food alias is too long" }
		require(normalizedValue.isNotBlank()) { "Food alias must contain searchable characters" }
		require(normalizedValue.length <= UserFoodLimits.ALIAS_MAX_LENGTH) {
			"Normalized food alias is too long"
		}
		require(normalizedValue == UserFoodTextNormalizer.normalize(value)) {
			"Food alias normalization is inconsistent"
		}
	}
}

data class UserFoodAlias(
	val aliasId: UUID,
	val value: String,
	val normalizedValue: String,
) {
	init {
		FoodAliasValue(value, normalizedValue)
	}
}

data class UserFoodDefinition(
	val name: String,
	val normalizedName: String,
	val brand: String?,
	val barcode: String?,
	val description: String?,
	val aliases: List<FoodAliasValue>,
	val nutritionBasis: NutritionBasis,
	val nutrients: NutrientValues,
	val defaultServing: DefaultServing?,
	val conversions: UnitConversions,
	val source: NutritionSource,
) {
	init {
		require(name.isNotBlank()) { "Food name must not be blank" }
		require(name.length <= UserFoodLimits.NAME_MAX_LENGTH) { "Food name is too long" }
		require(normalizedName.isNotBlank()) { "Food name must contain searchable characters" }
		require(normalizedName.length <= UserFoodLimits.NAME_MAX_LENGTH) { "Normalized food name is too long" }
		require(normalizedName == UserFoodTextNormalizer.normalize(name)) {
			"Food name normalization is inconsistent"
		}
		require(brand == null || brand.isNotBlank()) { "Brand must be null or non-blank" }
		require(brand == null || brand.length <= UserFoodLimits.BRAND_MAX_LENGTH) { "Brand is too long" }
		require(barcode == null || BARCODE_PATTERN.matches(barcode)) {
			"Barcode must contain between 8 and 14 digits"
		}
		require(description == null || description.isNotBlank()) {
			"Description must be null or non-blank"
		}
		require(description == null || description.length <= UserFoodLimits.DESCRIPTION_MAX_LENGTH) {
			"Description is too long"
		}
		require(aliases.size <= UserFoodLimits.ALIAS_MAX_COUNT) { "Too many food aliases" }
		require(aliases.map(FoodAliasValue::normalizedValue).distinct().size == aliases.size) {
			"Food aliases must be unique after normalization"
		}
		if (defaultServing != null) {
			require(conversions.supports(nutritionBasis.unit, defaultServing.unit)) {
				"Default serving cannot be converted to the nutrition basis without explicit metadata"
			}
		}
	}

	companion object {
		private val BARCODE_PATTERN = Regex("^[0-9]{8,14}$")

		fun from(
			name: String,
			brand: String?,
			barcode: String?,
			description: String?,
			aliases: List<String>,
			nutritionBasis: NutritionBasis,
			nutrients: NutrientValues,
			defaultServing: DefaultServing?,
			conversions: UnitConversions,
			source: NutritionSource,
		): UserFoodDefinition {
			val displayName = UserFoodTextNormalizer.displayValue(name)
			val aliasValues = aliases.map { alias ->
				val displayAlias = UserFoodTextNormalizer.displayValue(alias)
				FoodAliasValue(displayAlias, UserFoodTextNormalizer.normalize(displayAlias))
			}
			return UserFoodDefinition(
				name = displayName,
				normalizedName = UserFoodTextNormalizer.normalize(displayName),
				brand = brand.normalizedOptionalDisplay(),
				barcode = barcode?.trim()?.takeIf(String::isNotEmpty),
				description = description?.trim()?.takeIf(String::isNotEmpty),
				aliases = aliasValues,
				nutritionBasis = nutritionBasis,
				nutrients = nutrients,
				defaultServing = defaultServing,
				conversions = conversions,
				source = source.copy(
					provider = source.provider.normalizedOptionalDisplay(),
					externalId = source.externalId?.trim()?.takeIf(String::isNotEmpty),
					notes = source.notes?.trim()?.takeIf(String::isNotEmpty),
				),
			)
		}
	}
}

data class UserFood(
	val foodId: UserFoodId,
	val userId: UserId,
	val name: String,
	val normalizedName: String,
	val brand: String?,
	val barcode: String?,
	val description: String?,
	val aliases: List<UserFoodAlias>,
	val nutritionBasis: NutritionBasis,
	val nutrients: NutrientValues,
	val defaultServing: DefaultServing?,
	val conversions: UnitConversions,
	val source: NutritionSource,
	val createdAt: Instant,
	val updatedAt: Instant,
	val deletedAt: Instant?,
	val version: Long,
) {
	val active: Boolean
		get() = deletedAt == null

	init {
		definition()
		require(aliases.map(UserFoodAlias::normalizedValue).distinct().size == aliases.size) {
			"Food aliases must be unique after normalization"
		}
		require(!updatedAt.isBefore(createdAt)) { "Food update cannot precede creation" }
		require(deletedAt == null || !deletedAt.isBefore(createdAt)) { "Food deletion cannot precede creation" }
		require(version >= 0) { "Food version must not be negative" }
	}

	fun replace(definition: UserFoodDefinition, at: Instant): UserFood {
		check(active) { "Deleted food cannot be updated" }
		val existingAliases = aliases.associateBy(UserFoodAlias::normalizedValue)
		return copy(
			name = definition.name,
			normalizedName = definition.normalizedName,
			brand = definition.brand,
			barcode = definition.barcode,
			description = definition.description,
			aliases = definition.aliases.map { alias ->
				existingAliases[alias.normalizedValue]?.copy(value = alias.value)
					?: UserFoodAlias(UUID.randomUUID(), alias.value, alias.normalizedValue)
			},
			nutritionBasis = definition.nutritionBasis,
			nutrients = definition.nutrients,
			defaultServing = definition.defaultServing,
			conversions = definition.conversions,
			source = definition.source,
			updatedAt = maxOf(updatedAt, at),
		)
	}

	fun softDelete(at: Instant): UserFood {
		check(active) { "Food is already deleted" }
		val effectiveAt = maxOf(updatedAt, at)
		return copy(deletedAt = effectiveAt, updatedAt = effectiveAt)
	}

	fun definition(): UserFoodDefinition = UserFoodDefinition(
		name = name,
		normalizedName = normalizedName,
		brand = brand,
		barcode = barcode,
		description = description,
		aliases = aliases.map { FoodAliasValue(it.value, it.normalizedValue) },
		nutritionBasis = nutritionBasis,
		nutrients = nutrients,
		defaultServing = defaultServing,
		conversions = conversions,
		source = source,
	)

	companion object {
		fun create(userId: UserId, definition: UserFoodDefinition, at: Instant): UserFood = UserFood(
			foodId = UserFoodId(UUID.randomUUID()),
			userId = userId,
			name = definition.name,
			normalizedName = definition.normalizedName,
			brand = definition.brand,
			barcode = definition.barcode,
			description = definition.description,
			aliases = definition.aliases.map { alias ->
				UserFoodAlias(UUID.randomUUID(), alias.value, alias.normalizedValue)
			},
			nutritionBasis = definition.nutritionBasis,
			nutrients = definition.nutrients,
			defaultServing = definition.defaultServing,
			conversions = definition.conversions,
			source = definition.source,
			createdAt = at,
			updatedAt = at,
			deletedAt = null,
			version = 0,
		)
	}
}

object UserFoodLimits {
	const val NAME_MAX_LENGTH = 160
	const val BRAND_MAX_LENGTH = 120
	const val DESCRIPTION_MAX_LENGTH = 1_000
	const val ALIAS_MAX_LENGTH = 120
	const val ALIAS_MAX_COUNT = 20
	const val SOURCE_PROVIDER_MAX_LENGTH = 120
	const val SOURCE_EXTERNAL_ID_MAX_LENGTH = 255
	const val SOURCE_NOTES_MAX_LENGTH = 1_000
	const val MAX_DECIMAL_SCALE = 6
	val MAX_NUMERIC_VALUE: BigDecimal = BigDecimal("1000000")
}

private fun validateNutrient(value: BigDecimal?, name: String) {
	require(value == null || value >= BigDecimal.ZERO) { "$name must not be negative" }
	require(value == null || value <= UserFoodLimits.MAX_NUMERIC_VALUE) { "$name is too large" }
	if (value != null) requireSupportedScale(value, name)
}

private fun validateConversion(value: BigDecimal?, name: String) {
	if (value != null) {
		requirePositiveAmount(value, name)
	}
}

private fun requirePositiveAmount(value: BigDecimal, name: String) {
	require(value > BigDecimal.ZERO) { "$name must be positive" }
	require(value <= UserFoodLimits.MAX_NUMERIC_VALUE) { "$name is too large" }
	requireSupportedScale(value, name)
}

private fun requireSupportedScale(value: BigDecimal, name: String) {
	val normalizedScale = value.stripTrailingZeros().scale().coerceAtLeast(0)
	require(normalizedScale <= UserFoodLimits.MAX_DECIMAL_SCALE) {
		"$name must have at most ${UserFoodLimits.MAX_DECIMAL_SCALE} decimal places"
	}
}

private fun String?.normalizedOptionalDisplay(): String? = this
	?.let(UserFoodTextNormalizer::displayValue)
	?.takeIf(String::isNotEmpty)
