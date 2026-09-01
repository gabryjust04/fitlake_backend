package com.fitlake.food.domain

import com.fitlake.user.domain.UserId
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals

class UserFoodDomainTest {
	@Test
	fun `normalization is accent punctuation case and whitespace insensitive`() {
		assertEquals("yogurt greco 0", UserFoodTextNormalizer.normalize("  YÒGURT---Greco  0% "))
		assertEquals("creme brulee", UserFoodTextNormalizer.normalize("Crème brûlée"))
		assertEquals("vitamina b12", UserFoodTextNormalizer.normalize("Vitamina B12"))
		val supplementaryIdeograph = String(Character.toChars(0x20000))
		assertEquals(
			"$supplementaryIdeograph food",
			UserFoodTextNormalizer.normalize(" $supplementaryIdeograph-food "),
		)
	}

	@Test
	fun `definition preserves unknown nutrients as null`() {
		val definition = definition(nutrients = NutrientValues(caloriesKcal = BigDecimal("62")))

		assertEquals(BigDecimal("62"), definition.nutrients.caloriesKcal)
		assertEquals(null, definition.nutrients.proteinGrams)
	}

	@Test
	fun `invalid amounts nutrients aliases and source metadata are rejected`() {
		assertFailsWith<IllegalArgumentException> { NutritionBasis(BigDecimal.ZERO, FoodUnit.GRAM) }
		assertFailsWith<IllegalArgumentException> { NutrientValues(fatGrams = BigDecimal("-0.1")) }
		assertFailsWith<IllegalArgumentException> {
			NutritionBasis(BigDecimal("0.0000001"), FoodUnit.GRAM)
		}
		assertFailsWith<IllegalArgumentException> {
			NutrientValues(proteinGrams = BigDecimal("1.1234567"))
		}
		assertFailsWith<IllegalArgumentException> { definition(name = " ") }
		assertFailsWith<IllegalArgumentException> { definition(name = "x".repeat(161)) }
		assertFailsWith<IllegalArgumentException> { definition(name = "\uFB03".repeat(54)) }
		assertFailsWith<IllegalArgumentException> {
			UserFoodDefinition.from(
				name = "Food",
				brand = null,
				barcode = "not-a-barcode",
				description = null,
				aliases = listOf("alias"),
				nutritionBasis = NutritionBasis(BigDecimal.ONE, FoodUnit.PIECE),
				nutrients = NutrientValues(),
				defaultServing = null,
				conversions = UnitConversions.NONE,
				source = NutritionSource(NutritionSourceType.USER_ENTERED),
			)
		}
		assertFailsWith<IllegalArgumentException> { definition(aliases = listOf(" ")) }
		assertFailsWith<IllegalArgumentException> { definition(aliases = listOf("x".repeat(121))) }
		assertFailsWith<IllegalArgumentException> { definition(aliases = listOf("\uFB03".repeat(41))) }
		assertFailsWith<IllegalArgumentException> {
			definition(aliases = (1..21).map { "alias $it" })
		}
		assertFailsWith<IllegalArgumentException> { definition(barcode = "1234567") }
		assertFailsWith<IllegalArgumentException> { definition(barcode = "123456789012345") }
		assertFailsWith<IllegalArgumentException> {
			definition(aliases = listOf("My Yogurt", "  my-yogurt "))
		}
		assertFailsWith<IllegalArgumentException> {
			NutritionSource(NutritionSourceType.EXTERNAL_DATABASE, provider = null, externalId = null)
		}
	}

	@Test
	fun `cross category default serving requires explicit non contradictory conversion`() {
		assertFailsWith<IllegalArgumentException> { DefaultServing(BigDecimal.ZERO, FoodUnit.GRAM) }
		assertFailsWith<IllegalArgumentException> { UnitConversions(gramsPerServing = BigDecimal.ZERO) }
		assertFailsWith<IllegalArgumentException> {
			definition(defaultServing = DefaultServing(BigDecimal.ONE, FoodUnit.PIECE))
		}
		assertFailsWith<IllegalArgumentException> {
			UnitConversions(gramsPerPiece = BigDecimal("12"), millilitersPerPiece = BigDecimal("10"))
		}
		assertFailsWith<IllegalArgumentException> {
			UnitConversions(gramsPerServing = BigDecimal("30"), millilitersPerServing = BigDecimal("250"))
		}

		val valid = definition(
			defaultServing = DefaultServing(BigDecimal("3"), FoodUnit.PIECE),
			conversions = UnitConversions(gramsPerPiece = BigDecimal("12")),
		)

		assertEquals(BigDecimal("12"), valid.conversions.gramsPerPiece)

		val volumePiece = definition(
			nutritionBasis = NutritionBasis(BigDecimal("100"), FoodUnit.MILLILITER),
			defaultServing = DefaultServing(BigDecimal.ONE, FoodUnit.PIECE),
			conversions = UnitConversions(millilitersPerPiece = BigDecimal("250")),
		)
		assertEquals(BigDecimal("250"), volumePiece.conversions.millilitersPerPiece)

		val massServing = definition(
			defaultServing = DefaultServing(BigDecimal.ONE, FoodUnit.SERVING),
			conversions = UnitConversions(gramsPerServing = BigDecimal("30")),
		)
		assertEquals(BigDecimal("30"), massServing.conversions.gramsPerServing)
	}

	@Test
	fun `replacement recomputes normalized fields replaces aliases and preserves stable alias ids`() {
		val now = Instant.parse("2026-07-31T12:00:00Z")
		val food = UserFood.create(UserId(UUID.randomUUID()), definition(), now)
		val existingAliasId = food.aliases.single().aliasId

		val replaced = food.replace(
			definition(
				name = "  Yògurt NUOVO ",
				aliases = listOf("Il Mio Yogurt", "colazione yogurt"),
			),
			now.plusSeconds(60),
		)

		assertEquals("yogurt nuovo", replaced.normalizedName)
		assertEquals(existingAliasId, replaced.aliases.first { it.normalizedValue == "il mio yogurt" }.aliasId)
		assertNotEquals(existingAliasId, replaced.aliases.first { it.normalizedValue == "colazione yogurt" }.aliasId)
	}

	private fun definition(
		name: String = "Yogurt greco",
		barcode: String? = "1234567890123",
		aliases: List<String> = listOf("Il mio yogurt"),
		nutrients: NutrientValues = NutrientValues(
			caloriesKcal = BigDecimal("62"),
			proteinGrams = BigDecimal("9.5"),
			carbohydratesGrams = BigDecimal("4.1"),
			fatGrams = BigDecimal("0.2"),
		),
		defaultServing: DefaultServing? = DefaultServing(BigDecimal("170"), FoodUnit.GRAM),
		conversions: UnitConversions = UnitConversions.NONE,
		nutritionBasis: NutritionBasis = NutritionBasis(BigDecimal("100"), FoodUnit.GRAM),
	) = UserFoodDefinition.from(
		name = name,
		brand = "Brand",
		barcode = barcode,
		description = "Breakfast yogurt",
		aliases = aliases,
		nutritionBasis = nutritionBasis,
		nutrients = nutrients,
		defaultServing = defaultServing,
		conversions = conversions,
		source = NutritionSource(NutritionSourceType.PRODUCT_LABEL),
	)
}
