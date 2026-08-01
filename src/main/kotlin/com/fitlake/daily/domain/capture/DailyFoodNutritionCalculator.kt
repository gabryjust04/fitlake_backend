package com.fitlake.daily.domain.capture

import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode

data class DailyFoodCalculation(
	val resolvedQuantity: DailyResolvedQuantity,
	val calculatedNutrition: DailyNutritionValues,
)

/**
 * Resolves an entered food quantity against an immutable user-food snapshot and
 * scales every known nutrient from the snapshotted nutrition basis.
 *
 * Persisted amounts and calculated nutrients are rounded to six decimal places
 * with [RoundingMode.HALF_UP]. Missing nutrient values remain missing.
 */
class DailyFoodNutritionCalculator {
	fun calculate(
		enteredQuantity: DailyEnteredQuantity,
		snapshot: DailyUserFoodSnapshot,
	): DailyFoodCalculation {
		val basis = snapshot.nutritionBasis.toCanonicalQuantity()
		val entered = enteredQuantity.resolveDefaultServing(snapshot.defaultServing).toCanonicalQuantity()
		val resolvedAmount = convertToBasisDimension(
			quantity = entered,
			basis = basis,
			conversions = snapshot.conversions,
		)
		val factor = divideWithInternalPrecision(resolvedAmount, basis.amount, "Nutrition calculation factor")
		if (factor > MAX_CALCULATION_FACTOR) {
			throw DailyFoodCalculationException("Nutrition calculation factor is outside the allowed range")
		}

		return DailyFoodCalculation(
			resolvedQuantity = DailyResolvedQuantity(
				amount = resolvedAmount.roundedForDaily("Resolved quantity"),
				unit = basis.unit.toResolvedUnit(),
			),
			calculatedNutrition = snapshot.nutrientsPerBasis.scaledBy(factor),
		)
	}

	private fun convertToBasisDimension(
		quantity: CanonicalQuantity,
		basis: CanonicalQuantity,
		conversions: DailyFoodConversionSnapshot,
	): BigDecimal {
		if (quantity.dimension == basis.dimension) return quantity.amount.checkedBound("Resolved quantity")

		val converted = when (quantity.dimension to basis.dimension) {
			FoodDimension.PIECE to FoodDimension.MASS ->
				multiplyWithInternalPrecision(quantity.amount, conversions.requireGramsPerPiece(), "Resolved quantity")
			FoodDimension.MASS to FoodDimension.PIECE ->
				divideWithInternalPrecision(quantity.amount, conversions.requireGramsPerPiece(), "Resolved quantity")
			FoodDimension.PIECE to FoodDimension.VOLUME ->
				multiplyWithInternalPrecision(quantity.amount, conversions.requireMillilitersPerPiece(), "Resolved quantity")
			FoodDimension.VOLUME to FoodDimension.PIECE ->
				divideWithInternalPrecision(quantity.amount, conversions.requireMillilitersPerPiece(), "Resolved quantity")
			FoodDimension.SERVING to FoodDimension.MASS ->
				multiplyWithInternalPrecision(quantity.amount, conversions.requireGramsPerServing(), "Resolved quantity")
			FoodDimension.MASS to FoodDimension.SERVING ->
				divideWithInternalPrecision(quantity.amount, conversions.requireGramsPerServing(), "Resolved quantity")
			FoodDimension.SERVING to FoodDimension.VOLUME ->
				multiplyWithInternalPrecision(quantity.amount, conversions.requireMillilitersPerServing(), "Resolved quantity")
			FoodDimension.VOLUME to FoodDimension.SERVING ->
				divideWithInternalPrecision(quantity.amount, conversions.requireMillilitersPerServing(), "Resolved quantity")
			else -> throw DailyFoodCalculationException(
				"Incompatible food quantity units: ${quantity.unit} cannot be converted to ${basis.unit}",
			)
		}
		return converted.checkedBound("Resolved quantity")
	}

	private fun DailyEnteredQuantity.resolveDefaultServing(
		defaultServing: DailyFoodDefaultServingSnapshot?,
	): SnapshotQuantity = if (unit == DailyFoodQuantityUnit.DEFAULT_SERVING) {
		val serving = defaultServing
			?: throw DailyFoodCalculationException("Default serving is not defined for this user food")
		SnapshotQuantity(
			amount = multiplyWithInternalPrecision(amount, serving.amount, "Resolved default serving quantity"),
			unit = serving.unit,
		)
	} else {
		SnapshotQuantity(amount, unit.toSnapshotUnit())
	}

	private fun SnapshotQuantity.toCanonicalQuantity(): CanonicalQuantity = when (unit) {
		DailyFoodSnapshotUnit.GRAM -> CanonicalQuantity(
			amount.checkedBound("Food quantity"),
			CanonicalFoodUnit.GRAM,
		)
		DailyFoodSnapshotUnit.KILOGRAM -> CanonicalQuantity(
			multiplyWithInternalPrecision(amount, ONE_THOUSAND, "Food quantity"),
			CanonicalFoodUnit.GRAM,
		)
		DailyFoodSnapshotUnit.MILLILITER -> CanonicalQuantity(
			amount.checkedBound("Food quantity"),
			CanonicalFoodUnit.MILLILITER,
		)
		DailyFoodSnapshotUnit.LITER -> CanonicalQuantity(
			multiplyWithInternalPrecision(amount, ONE_THOUSAND, "Food quantity"),
			CanonicalFoodUnit.MILLILITER,
		)
		DailyFoodSnapshotUnit.PIECE -> CanonicalQuantity(
			amount.checkedBound("Food quantity"),
			CanonicalFoodUnit.PIECE,
		)
		DailyFoodSnapshotUnit.SERVING -> CanonicalQuantity(
			amount.checkedBound("Food quantity"),
			CanonicalFoodUnit.SERVING,
		)
	}

	private fun DailyFoodBasisSnapshot.toCanonicalQuantity(): CanonicalQuantity =
		SnapshotQuantity(amount, unit).toCanonicalQuantity()

	private fun DailyNutritionValues.scaledBy(factor: BigDecimal): DailyNutritionValues = DailyNutritionValues(
		caloriesKcal = caloriesKcal.scaledNutrient(factor, "Calculated calories"),
		proteinGrams = proteinGrams.scaledNutrient(factor, "Calculated protein"),
		carbohydratesGrams = carbohydratesGrams.scaledNutrient(factor, "Calculated carbohydrates"),
		fatGrams = fatGrams.scaledNutrient(factor, "Calculated fat"),
		fiberGrams = fiberGrams.scaledNutrient(factor, "Calculated fiber"),
		sugarsGrams = sugarsGrams.scaledNutrient(factor, "Calculated sugars"),
		saturatedFatGrams = saturatedFatGrams.scaledNutrient(factor, "Calculated saturated fat"),
		sodiumMilligrams = sodiumMilligrams.scaledNutrient(factor, "Calculated sodium"),
		saltGrams = saltGrams.scaledNutrient(factor, "Calculated salt"),
	)

	private fun BigDecimal?.scaledNutrient(factor: BigDecimal, name: String): BigDecimal? =
		this?.let { multiplyWithInternalPrecision(it, factor, name).roundedForDaily(name) }

	private fun DailyFoodQuantityUnit.toSnapshotUnit(): DailyFoodSnapshotUnit = when (this) {
		DailyFoodQuantityUnit.GRAM -> DailyFoodSnapshotUnit.GRAM
		DailyFoodQuantityUnit.KILOGRAM -> DailyFoodSnapshotUnit.KILOGRAM
		DailyFoodQuantityUnit.MILLILITER -> DailyFoodSnapshotUnit.MILLILITER
		DailyFoodQuantityUnit.LITER -> DailyFoodSnapshotUnit.LITER
		DailyFoodQuantityUnit.PIECE -> DailyFoodSnapshotUnit.PIECE
		DailyFoodQuantityUnit.SERVING -> DailyFoodSnapshotUnit.SERVING
		DailyFoodQuantityUnit.DEFAULT_SERVING -> error("Default serving must be resolved before unit conversion")
	}

	private fun CanonicalFoodUnit.toResolvedUnit(): DailyResolvedFoodUnit = when (this) {
		CanonicalFoodUnit.GRAM -> DailyResolvedFoodUnit.GRAM
		CanonicalFoodUnit.MILLILITER -> DailyResolvedFoodUnit.MILLILITER
		CanonicalFoodUnit.PIECE -> DailyResolvedFoodUnit.PIECE
		CanonicalFoodUnit.SERVING -> DailyResolvedFoodUnit.SERVING
	}

	private fun DailyFoodConversionSnapshot.requireGramsPerPiece(): BigDecimal = gramsPerPiece
		?: throw DailyFoodCalculationException("Piece-to-mass conversion requires gramsPerPiece")

	private fun DailyFoodConversionSnapshot.requireMillilitersPerPiece(): BigDecimal = millilitersPerPiece
		?: throw DailyFoodCalculationException("Piece-to-volume conversion requires millilitersPerPiece")

	private fun DailyFoodConversionSnapshot.requireGramsPerServing(): BigDecimal = gramsPerServing
		?: throw DailyFoodCalculationException("Serving-to-mass conversion requires gramsPerServing")

	private fun DailyFoodConversionSnapshot.requireMillilitersPerServing(): BigDecimal = millilitersPerServing
		?: throw DailyFoodCalculationException("Serving-to-volume conversion requires millilitersPerServing")

	private fun multiplyWithInternalPrecision(left: BigDecimal, right: BigDecimal, name: String): BigDecimal =
		left.multiply(right, INTERNAL_CONTEXT).checkedBound(name)

	private fun divideWithInternalPrecision(dividend: BigDecimal, divisor: BigDecimal, name: String): BigDecimal {
		if (divisor <= BigDecimal.ZERO) {
			throw DailyFoodCalculationException("$name requires a positive divisor")
		}
		return dividend.divide(divisor, INTERNAL_CONTEXT).checkedBound(name)
	}

	private fun BigDecimal.roundedForDaily(name: String): BigDecimal {
		return setScale(OUTPUT_SCALE, OUTPUT_ROUNDING).checkedBound(name)
	}

	private fun BigDecimal.checkedBound(name: String): BigDecimal {
		if (abs() > MAX_CALCULATED_VALUE) {
			throw DailyFoodCalculationException("$name is outside the allowed range")
		}
		return this
	}

	private data class SnapshotQuantity(
		val amount: BigDecimal,
		val unit: DailyFoodSnapshotUnit,
	)

	private data class CanonicalQuantity(
		val amount: BigDecimal,
		val unit: CanonicalFoodUnit,
	) {
		val dimension: FoodDimension = unit.dimension
	}

	private enum class CanonicalFoodUnit(val dimension: FoodDimension) {
		GRAM(FoodDimension.MASS),
		MILLILITER(FoodDimension.VOLUME),
		PIECE(FoodDimension.PIECE),
		SERVING(FoodDimension.SERVING),
	}

	private enum class FoodDimension {
		MASS,
		VOLUME,
		PIECE,
		SERVING,
	}

	companion object {
		const val OUTPUT_SCALE = 6
		val OUTPUT_ROUNDING: RoundingMode = RoundingMode.HALF_UP
		private val INTERNAL_CONTEXT = MathContext.DECIMAL128
		private val ONE_THOUSAND = BigDecimal("1000")
		private val MAX_CALCULATION_FACTOR = BigDecimal("1000000000000")
		private val MAX_CALCULATED_VALUE = BigDecimal("1000000000000")
	}
}

class DailyFoodCalculationException(message: String) : IllegalArgumentException(message)
