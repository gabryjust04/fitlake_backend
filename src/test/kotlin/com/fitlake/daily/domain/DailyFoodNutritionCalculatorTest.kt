package com.fitlake.daily.domain

import com.fitlake.daily.domain.capture.DailyEnteredQuantity
import com.fitlake.daily.domain.capture.DailyFoodBasisSnapshot
import com.fitlake.daily.domain.capture.DailyFoodCalculationException
import com.fitlake.daily.domain.capture.DailyFoodConversionSnapshot
import com.fitlake.daily.domain.capture.DailyFoodDefaultServingSnapshot
import com.fitlake.daily.domain.capture.DailyFoodItemSourceType
import com.fitlake.daily.domain.capture.DailyFoodNutritionCalculator
import com.fitlake.daily.domain.capture.DailyFoodQuantityUnit
import com.fitlake.daily.domain.capture.DailyFoodSnapshotUnit
import com.fitlake.daily.domain.capture.DailyNutritionSourceSnapshot
import com.fitlake.daily.domain.capture.DailyNutritionValues
import com.fitlake.daily.domain.capture.DailyResolvedFoodUnit
import com.fitlake.daily.domain.capture.DailyUserFoodSnapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import java.math.BigDecimal
import java.time.Instant

class DailyFoodNutritionCalculatorTest {
	private val calculator = DailyFoodNutritionCalculator()

	@Test
	fun `grams scale all known nutrients from a per-100-gram basis`() {
		val result = calculator.calculate(
			entered("170", DailyFoodQuantityUnit.GRAM),
			snapshot(
				basisAmount = "100",
				basisUnit = DailyFoodSnapshotUnit.GRAM,
				nutrients = completeNutrients(),
			),
		)

		assertResolved("170", DailyResolvedFoodUnit.GRAM, result.resolvedQuantity.amount, result.resolvedQuantity.unit)
		assertDecimal("105.4", result.calculatedNutrition.caloriesKcal)
		assertDecimal("16.15", result.calculatedNutrition.proteinGrams)
		assertDecimal("6.97", result.calculatedNutrition.carbohydratesGrams)
		assertDecimal("0.34", result.calculatedNutrition.fatGrams)
		assertDecimal("0.85", result.calculatedNutrition.fiberGrams)
		assertDecimal("6.97", result.calculatedNutrition.sugarsGrams)
		assertDecimal("0.17", result.calculatedNutrition.saturatedFatGrams)
		assertDecimal("68", result.calculatedNutrition.sodiumMilligrams)
		assertDecimal("0.17", result.calculatedNutrition.saltGrams)
	}

	@Test
	fun `kilograms are converted exactly to canonical grams`() {
		val result = calculator.calculate(
			entered("0.25", DailyFoodQuantityUnit.KILOGRAM),
			snapshot("100", DailyFoodSnapshotUnit.GRAM, nutrients(calories = "62")),
		)

		assertResolved("250", DailyResolvedFoodUnit.GRAM, result.resolvedQuantity.amount, result.resolvedQuantity.unit)
		assertDecimal("155", result.calculatedNutrition.caloriesKcal)
	}

	@Test
	fun `milliliters scale a volume basis`() {
		val result = calculator.calculate(
			entered("250", DailyFoodQuantityUnit.MILLILITER),
			snapshot("100", DailyFoodSnapshotUnit.MILLILITER, nutrients(calories = "40")),
		)

		assertResolved("250", DailyResolvedFoodUnit.MILLILITER, result.resolvedQuantity.amount, result.resolvedQuantity.unit)
		assertDecimal("100", result.calculatedNutrition.caloriesKcal)
	}

	@Test
	fun `liters are converted exactly to canonical milliliters`() {
		val result = calculator.calculate(
			entered("0.25", DailyFoodQuantityUnit.LITER),
			snapshot("100", DailyFoodSnapshotUnit.MILLILITER, nutrients(calories = "40")),
		)

		assertResolved("250", DailyResolvedFoodUnit.MILLILITER, result.resolvedQuantity.amount, result.resolvedQuantity.unit)
		assertDecimal("100", result.calculatedNutrition.caloriesKcal)
	}

	@Test
	fun `kilogram and liter nutrition bases are canonicalized before scaling`() {
		val mass = calculator.calculate(
			entered("250", DailyFoodQuantityUnit.GRAM),
			snapshot("0.1", DailyFoodSnapshotUnit.KILOGRAM, nutrients(calories = "62")),
		)
		val volume = calculator.calculate(
			entered("250", DailyFoodQuantityUnit.MILLILITER),
			snapshot("0.1", DailyFoodSnapshotUnit.LITER, nutrients(calories = "40")),
		)

		assertResolved("250", DailyResolvedFoodUnit.GRAM, mass.resolvedQuantity.amount, mass.resolvedQuantity.unit)
		assertDecimal("155", mass.calculatedNutrition.caloriesKcal)
		assertResolved("250", DailyResolvedFoodUnit.MILLILITER, volume.resolvedQuantity.amount, volume.resolvedQuantity.unit)
		assertDecimal("100", volume.calculatedNutrition.caloriesKcal)
	}

	@Test
	fun `piece nutrition scales directly when the basis is per piece`() {
		val result = calculator.calculate(
			entered("3", DailyFoodQuantityUnit.PIECE),
			snapshot("1", DailyFoodSnapshotUnit.PIECE, nutrients(calories = "50", protein = "2.5")),
		)

		assertResolved("3", DailyResolvedFoodUnit.PIECE, result.resolvedQuantity.amount, result.resolvedQuantity.unit)
		assertDecimal("150", result.calculatedNutrition.caloriesKcal)
		assertDecimal("7.5", result.calculatedNutrition.proteinGrams)
	}

	@Test
	fun `grams-per-piece converts pieces to a mass basis`() {
		val result = calculator.calculate(
			entered("3", DailyFoodQuantityUnit.PIECE),
			snapshot(
				basisAmount = "100",
				basisUnit = DailyFoodSnapshotUnit.GRAM,
				nutrients = nutrients(calories = "100"),
				conversions = DailyFoodConversionSnapshot(gramsPerPiece = bd("12")),
			),
		)

		assertResolved("36", DailyResolvedFoodUnit.GRAM, result.resolvedQuantity.amount, result.resolvedQuantity.unit)
		assertDecimal("36", result.calculatedNutrition.caloriesKcal)
	}

	@Test
	fun `grams-per-piece converts consumed mass to a piece basis`() {
		val result = calculator.calculate(
			entered("36", DailyFoodQuantityUnit.GRAM),
			snapshot(
				basisAmount = "1",
				basisUnit = DailyFoodSnapshotUnit.PIECE,
				nutrients = nutrients(calories = "50"),
				conversions = DailyFoodConversionSnapshot(gramsPerPiece = bd("12")),
			),
		)

		assertResolved("3", DailyResolvedFoodUnit.PIECE, result.resolvedQuantity.amount, result.resolvedQuantity.unit)
		assertDecimal("150", result.calculatedNutrition.caloriesKcal)
	}

	@Test
	fun `milliliters-per-piece converts pieces to a volume basis`() {
		val result = calculator.calculate(
			entered("2", DailyFoodQuantityUnit.PIECE),
			snapshot(
				basisAmount = "100",
				basisUnit = DailyFoodSnapshotUnit.MILLILITER,
				nutrients = nutrients(calories = "25"),
				conversions = DailyFoodConversionSnapshot(millilitersPerPiece = bd("125")),
			),
		)

		assertResolved("250", DailyResolvedFoodUnit.MILLILITER, result.resolvedQuantity.amount, result.resolvedQuantity.unit)
		assertDecimal("62.5", result.calculatedNutrition.caloriesKcal)
	}

	@Test
	fun `servings scale directly and explicit grams-per-serving bridges to mass`() {
		val direct = calculator.calculate(
			entered("2", DailyFoodQuantityUnit.SERVING),
			snapshot("1", DailyFoodSnapshotUnit.SERVING, nutrients(calories = "250")),
		)
		val bridged = calculator.calculate(
			entered("2", DailyFoodQuantityUnit.SERVING),
			snapshot(
				basisAmount = "100",
				basisUnit = DailyFoodSnapshotUnit.GRAM,
				nutrients = nutrients(calories = "200"),
				conversions = DailyFoodConversionSnapshot(gramsPerServing = bd("75")),
			),
		)

		assertResolved("2", DailyResolvedFoodUnit.SERVING, direct.resolvedQuantity.amount, direct.resolvedQuantity.unit)
		assertDecimal("500", direct.calculatedNutrition.caloriesKcal)
		assertResolved("150", DailyResolvedFoodUnit.GRAM, bridged.resolvedQuantity.amount, bridged.resolvedQuantity.unit)
		assertDecimal("300", bridged.calculatedNutrition.caloriesKcal)
	}

	@Test
	fun `milliliters-per-serving bridges serving and volume in both directions`() {
		val servingToVolume = calculator.calculate(
			entered("2", DailyFoodQuantityUnit.SERVING),
			snapshot(
				basisAmount = "100",
				basisUnit = DailyFoodSnapshotUnit.MILLILITER,
				nutrients = nutrients(calories = "10"),
				conversions = DailyFoodConversionSnapshot(millilitersPerServing = bd("150")),
			),
		)
		val volumeToServing = calculator.calculate(
			entered("300", DailyFoodQuantityUnit.MILLILITER),
			snapshot(
				basisAmount = "1",
				basisUnit = DailyFoodSnapshotUnit.SERVING,
				nutrients = nutrients(calories = "15"),
				conversions = DailyFoodConversionSnapshot(millilitersPerServing = bd("150")),
			),
		)

		assertResolved("300", DailyResolvedFoodUnit.MILLILITER, servingToVolume.resolvedQuantity.amount, servingToVolume.resolvedQuantity.unit)
		assertDecimal("30", servingToVolume.calculatedNutrition.caloriesKcal)
		assertResolved("2", DailyResolvedFoodUnit.SERVING, volumeToServing.resolvedQuantity.amount, volumeToServing.resolvedQuantity.unit)
		assertDecimal("30", volumeToServing.calculatedNutrition.caloriesKcal)
	}

	@Test
	fun `default serving supports multipliers and resolves through its saved unit`() {
		val result = calculator.calculate(
			entered("2", DailyFoodQuantityUnit.DEFAULT_SERVING),
			snapshot(
				basisAmount = "100",
				basisUnit = DailyFoodSnapshotUnit.GRAM,
				nutrients = nutrients(calories = "62", protein = "9.5"),
				defaultServing = DailyFoodDefaultServingSnapshot(bd("170"), DailyFoodSnapshotUnit.GRAM),
			),
		)

		assertResolved("340", DailyResolvedFoodUnit.GRAM, result.resolvedQuantity.amount, result.resolvedQuantity.unit)
		assertDecimal("210.8", result.calculatedNutrition.caloriesKcal)
		assertDecimal("32.3", result.calculatedNutrition.proteinGrams)
	}

	@Test
	fun `default serving may use piece conversion from the immutable snapshot`() {
		val result = calculator.calculate(
			entered("1.5", DailyFoodQuantityUnit.DEFAULT_SERVING),
			snapshot(
				basisAmount = "100",
				basisUnit = DailyFoodSnapshotUnit.GRAM,
				nutrients = nutrients(calories = "100"),
				defaultServing = DailyFoodDefaultServingSnapshot(bd("2"), DailyFoodSnapshotUnit.PIECE),
				conversions = DailyFoodConversionSnapshot(gramsPerPiece = bd("12")),
			),
		)

		assertResolved("36", DailyResolvedFoodUnit.GRAM, result.resolvedQuantity.amount, result.resolvedQuantity.unit)
		assertDecimal("36", result.calculatedNutrition.caloriesKcal)
	}

	@Test
	fun `missing default serving is rejected`() {
		val error = assertFailsWith<DailyFoodCalculationException> {
			calculator.calculate(
				entered("1", DailyFoodQuantityUnit.DEFAULT_SERVING),
				snapshot("100", DailyFoodSnapshotUnit.GRAM, nutrients(calories = "62")),
			)
		}

		assertTrue(error.message.orEmpty().contains("Default serving"))
	}

	@Test
	fun `incompatible mass-to-volume and piece-to-serving conversions are rejected without guessing`() {
		assertFailsWith<DailyFoodCalculationException> {
			calculator.calculate(
				entered("100", DailyFoodQuantityUnit.GRAM),
				snapshot("100", DailyFoodSnapshotUnit.MILLILITER, nutrients(calories = "40")),
			)
		}
		assertFailsWith<DailyFoodCalculationException> {
			calculator.calculate(
				entered("2", DailyFoodQuantityUnit.PIECE),
				snapshot(
					basisAmount = "1",
					basisUnit = DailyFoodSnapshotUnit.SERVING,
					nutrients = nutrients(calories = "40"),
					conversions = DailyFoodConversionSnapshot(
						gramsPerPiece = bd("12"),
						gramsPerServing = bd("24"),
					),
				),
			)
		}
	}

	@Test
	fun `piece and serving conversions require their explicit metadata`() {
		val pieceError = assertFailsWith<DailyFoodCalculationException> {
			calculator.calculate(
				entered("3", DailyFoodQuantityUnit.PIECE),
				snapshot("100", DailyFoodSnapshotUnit.GRAM, nutrients(calories = "100")),
			)
		}
		val servingError = assertFailsWith<DailyFoodCalculationException> {
			calculator.calculate(
				entered("1", DailyFoodQuantityUnit.SERVING),
				snapshot("100", DailyFoodSnapshotUnit.MILLILITER, nutrients(calories = "100")),
			)
		}

		assertTrue(pieceError.message.orEmpty().contains("gramsPerPiece"))
		assertTrue(servingError.message.orEmpty().contains("millilitersPerServing"))
	}

	@Test
	fun `division keeps internal precision and rounds only output to scale six half up`() {
		val oneThird = calculator.calculate(
			entered("1", DailyFoodQuantityUnit.GRAM),
			snapshot("3", DailyFoodSnapshotUnit.GRAM, nutrients(calories = "1")),
		)
		val twoThirds = calculator.calculate(
			entered("2", DailyFoodQuantityUnit.GRAM),
			snapshot("3", DailyFoodSnapshotUnit.GRAM, nutrients(calories = "1")),
		)

		assertEquals(BigDecimal("0.333333"), oneThird.calculatedNutrition.caloriesKcal)
		assertEquals(BigDecimal("0.666667"), twoThirds.calculatedNutrition.caloriesKcal)
		assertEquals(6, oneThird.resolvedQuantity.amount.scale())
	}

	@Test
	fun `cross-dimension conversion does not round before nutrient scaling`() {
		val result = calculator.calculate(
			entered("1", DailyFoodQuantityUnit.GRAM),
			snapshot(
				basisAmount = "0.1",
				basisUnit = DailyFoodSnapshotUnit.PIECE,
				nutrients = nutrients(calories = "1"),
				conversions = DailyFoodConversionSnapshot(gramsPerPiece = bd("3")),
			),
		)

		assertEquals(BigDecimal("0.333333"), result.resolvedQuantity.amount)
		assertEquals(BigDecimal("3.333333"), result.calculatedNutrition.caloriesKcal)
	}

	@Test
	fun `unknown nutrient values remain null rather than becoming zero`() {
		val result = calculator.calculate(
			entered("250", DailyFoodQuantityUnit.GRAM),
			snapshot(
				basisAmount = "100",
				basisUnit = DailyFoodSnapshotUnit.GRAM,
				nutrients = nutrients(calories = "50", protein = null),
			),
		)

		assertDecimal("125", result.calculatedNutrition.caloriesKcal)
		assertNull(result.calculatedNutrition.proteinGrams)
		assertNull(result.calculatedNutrition.carbohydratesGrams)
		assertNull(result.calculatedNutrition.sodiumMilligrams)
	}

	@Test
	fun `zero and negative consumed quantities and invalid bases are rejected before calculation`() {
		assertFailsWith<IllegalArgumentException> {
			DailyEnteredQuantity(BigDecimal.ZERO, DailyFoodQuantityUnit.GRAM)
		}
		assertFailsWith<IllegalArgumentException> {
			DailyEnteredQuantity(BigDecimal("-1"), DailyFoodQuantityUnit.GRAM)
		}
		assertFailsWith<IllegalArgumentException> {
			DailyFoodBasisSnapshot(BigDecimal.ZERO, DailyFoodSnapshotUnit.GRAM)
		}
	}

	@Test
	fun `resolved quantities calculation factors and nutrient outputs enforce technical bounds`() {
		assertFailsWith<DailyFoodCalculationException> {
			calculator.calculate(
				entered("1000000000000", DailyFoodQuantityUnit.KILOGRAM),
				snapshot("100", DailyFoodSnapshotUnit.GRAM, nutrients(calories = "1")),
			)
		}
		assertFailsWith<DailyFoodCalculationException> {
			calculator.calculate(
				entered("10000000", DailyFoodQuantityUnit.GRAM),
				snapshot("0.000001", DailyFoodSnapshotUnit.GRAM, nutrients(calories = "1")),
			)
		}
		assertFailsWith<DailyFoodCalculationException> {
			calculator.calculate(
				entered("1000000000000", DailyFoodQuantityUnit.GRAM),
				snapshot("1", DailyFoodSnapshotUnit.GRAM, nutrients(calories = "2")),
			)
		}
	}

	private fun entered(amount: String, unit: DailyFoodQuantityUnit) = DailyEnteredQuantity(bd(amount), unit)

	private fun snapshot(
		basisAmount: String,
		basisUnit: DailyFoodSnapshotUnit,
		nutrients: DailyNutritionValues,
		defaultServing: DailyFoodDefaultServingSnapshot? = null,
		conversions: DailyFoodConversionSnapshot = DailyFoodConversionSnapshot(),
	) = DailyUserFoodSnapshot(
		nutritionBasis = DailyFoodBasisSnapshot(bd(basisAmount), basisUnit),
		nutrientsPerBasis = nutrients,
		defaultServing = defaultServing,
		conversions = conversions,
		nutritionSource = DailyNutritionSourceSnapshot(
			type = DailyFoodItemSourceType.USER_FOOD,
			originalSourceType = "PRODUCT_LABEL",
			estimated = false,
		),
		userFoodVersion = 3,
		userFoodUpdatedAt = Instant.parse("2026-07-31T10:00:00Z"),
	)

	private fun completeNutrients() = DailyNutritionValues(
		caloriesKcal = bd("62"),
		proteinGrams = bd("9.5"),
		carbohydratesGrams = bd("4.1"),
		fatGrams = bd("0.2"),
		fiberGrams = bd("0.5"),
		sugarsGrams = bd("4.1"),
		saturatedFatGrams = bd("0.1"),
		sodiumMilligrams = bd("40"),
		saltGrams = bd("0.1"),
	)

	private fun nutrients(
		calories: String? = null,
		protein: String? = null,
	) = DailyNutritionValues(
		caloriesKcal = calories?.let(::bd),
		proteinGrams = protein?.let(::bd),
	)

	private fun assertResolved(
		expectedAmount: String,
		expectedUnit: DailyResolvedFoodUnit,
		actualAmount: BigDecimal,
		actualUnit: DailyResolvedFoodUnit,
	) {
		assertDecimal(expectedAmount, actualAmount)
		assertEquals(expectedUnit, actualUnit)
	}

	private fun assertDecimal(expected: String, actual: BigDecimal?) {
		val value = requireNotNull(actual) { "Expected decimal $expected but was null" }
		assertEquals(0, bd(expected).compareTo(value), "Expected $expected but was $value")
		assertTrue(value.scale() <= DailyFoodNutritionCalculator.OUTPUT_SCALE)
	}

	private fun bd(value: String) = BigDecimal(value)
}
