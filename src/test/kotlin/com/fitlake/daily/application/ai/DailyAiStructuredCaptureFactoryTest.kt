package com.fitlake.daily.application.ai

import com.fitlake.daily.application.port.DailyAiUserFoodMatchPort
import com.fitlake.daily.application.port.DailyAiUserFoodMatchResult
import com.fitlake.daily.application.port.DailyOwnedUserFood
import com.fitlake.daily.domain.capture.DailyCaptureEntryType
import com.fitlake.daily.domain.capture.DailyFoodBasisSnapshot
import com.fitlake.daily.domain.capture.DailyFoodConversionSnapshot
import com.fitlake.daily.domain.capture.DailyFoodDefaultServingSnapshot
import com.fitlake.daily.domain.capture.DailyFoodItemSourceType
import com.fitlake.daily.domain.capture.DailyFoodQuantityUnit
import com.fitlake.daily.domain.capture.DailyFoodSnapshotUnit
import com.fitlake.daily.domain.capture.DailyNutritionSourceSnapshot
import com.fitlake.daily.domain.capture.DailyNutritionValues
import com.fitlake.user.domain.UserId
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class DailyAiStructuredCaptureFactoryTest {
	private val userId = UserId(UUID.randomUUID())

	@Test
	fun `AI estimate is scaled by the backend from its explicit basis`() {
		val raw = "Ho mangiato 50 g di pollo"
		val result = factory(DailyAiUserFoodMatchResult.None).create(
			userId,
			raw,
			foodInterpretation(
				raw = raw,
				fragment = "50 g di pollo",
				stated = AiFoodQuantity(bd("50"), "g"),
				estimated = AiFoodQuantity(bd("50"), "g"),
				basis = AiFoodQuantity(bd("100"), "g"),
			),
		)

		val item = result.payload.entries.single().items.single()
		assertEquals(DailyFoodItemSourceType.AI_ESTIMATE, item.sourceType)
		assertDecimal("82.5", item.calculatedNutrition.caloriesKcal)
		assertDecimal("15.5", item.calculatedNutrition.proteinGrams)
		assertEquals(DailyAiQuantitySource.STATED, result.nutritionResolutions.single().quantitySource)
	}

	@Test
	fun `matched default serving wins when the user did not state a quantity`() {
		val raw = "Ho mangiato yogurt"
		val matched = DailyAiUserFoodMatchResult.Unique(catalogFood())
		val result = factory(matched).create(
			userId,
			raw,
			foodInterpretation(
				raw = raw,
				fragment = "yogurt",
				stated = null,
				estimated = AiFoodQuantity(bd("180"), "g"),
				basis = AiFoodQuantity(bd("100"), "g"),
			),
		)

		val item = result.payload.entries.single().items.single()
		assertEquals(DailyFoodItemSourceType.USER_FOOD, item.sourceType)
		assertEquals(DailyFoodQuantityUnit.DEFAULT_SERVING, item.enteredQuantity.unit)
		assertDecimal("125", item.calculatedNutrition.caloriesKcal)
		assertEquals(DailyAiQuantitySource.MATCHED_DEFAULT_SERVING, result.nutritionResolutions.single().quantitySource)
	}

	@Test
	fun `AI estimated quantity is used when neither stated quantity nor safe catalog match exists`() {
		val raw = "Ho mangiato una ciotola di zuppa"
		val result = factory(DailyAiUserFoodMatchResult.None).create(
			userId,
			raw,
			foodInterpretation(
				raw = raw,
				fragment = "una ciotola di zuppa",
				stated = null,
				estimated = AiFoodQuantity(bd("300"), "g"),
				basis = AiFoodQuantity(bd("100"), "g"),
			),
		)

		val item = result.payload.entries.single().items.single()
		assertDecimal("300", item.enteredQuantity.amount)
		assertDecimal("495", item.calculatedNutrition.caloriesKcal)
		assertEquals(DailyAiQuantitySource.AI_ESTIMATED, result.nutritionResolutions.single().quantitySource)
	}

	@Test
	fun `PARTIAL keeps structured facts and exact unresolved fragments as NOTE entries`() {
		val raw = "Peso 78 kg e poi una cosa strana"
		val result = factory(DailyAiUserFoodMatchResult.None).create(
			userId,
			raw,
			DailyMessageInterpretation(
				outcome = DailyMessageInterpretationOutcome.PARTIAL,
				fields = listOf(
					AiDailyFieldInterpretation(
						field = AiDailyFieldType.BODY_WEIGHT_KG,
						numericValue = bd("78"),
						originalFragment = "Peso 78 kg",
					),
				),
				unresolvedFragments = listOf("una cosa strana"),
			),
		)

		assertEquals(
			setOf(DailyCaptureEntryType.WEIGHT, DailyCaptureEntryType.NOTE),
			result.payload.entries.map { it.type }.toSet(),
		)
		assertEquals("una cosa strana", result.payload.entries.single { it.type == DailyCaptureEntryType.NOTE }.text)
	}

	@Test
	fun `paraphrased unresolved content is rejected and UNRESOLVED stores the complete original message`() {
		val raw = "Mi sento boh in un modo difficile da descrivere"
		assertFailsWith<DailyAiInvalidOutputException> {
			factory(DailyAiUserFoodMatchResult.None).create(
				userId,
				raw,
				DailyMessageInterpretation(
					outcome = DailyMessageInterpretationOutcome.PARTIAL,
					fields = listOf(
						AiDailyFieldInterpretation(
							AiDailyFieldType.MOOD_LEVEL,
							numericValue = bd("5"),
							originalFragment = "Mi sento boh",
						),
					),
					unresolvedFragments = listOf("sensazione non descrivibile"),
				),
			)
		}

		val unresolved = factory(DailyAiUserFoodMatchResult.None).create(
			userId,
			raw,
			DailyMessageInterpretation(DailyMessageInterpretationOutcome.UNRESOLVED),
		)
		assertEquals(raw, unresolved.payload.entries.single().text)
	}

	private fun foodInterpretation(
		raw: String,
		fragment: String,
		stated: AiFoodQuantity?,
		estimated: AiFoodQuantity,
		basis: AiFoodQuantity,
	) = DailyMessageInterpretation(
		outcome = DailyMessageInterpretationOutcome.COMPLETE,
		meals = listOf(
			AiMealInterpretation(
				items = listOf(
					AiFoodInterpretation(
						originalFragment = fragment,
						searchText = if (raw.contains("yogurt")) "yogurt" else if (raw.contains("zuppa")) "zuppa" else "pollo",
						statedQuantity = stated,
						estimatedQuantity = estimated,
						nutritionEstimate = AiNutritionEstimate(
							basis = basis,
							caloriesKcal = bd("165"),
							proteinGrams = bd("31"),
							carbohydratesGrams = bd("0"),
							fatGrams = bd("3.6"),
						),
					),
				),
			),
		),
	)

	private fun factory(result: DailyAiUserFoodMatchResult) = DailyAiCaptureProposalFactory(
		DailyAiUserFoodMatchPort { _, _ -> result },
	)

	private fun catalogFood() = DailyOwnedUserFood(
		userFoodId = UUID.randomUUID(),
		displayName = "Yogurt catalogo",
		brand = null,
		nutritionBasis = DailyFoodBasisSnapshot(bd("100"), DailyFoodSnapshotUnit.GRAM),
		nutrientsPerBasis = DailyNutritionValues(
			caloriesKcal = bd("50"),
			proteinGrams = bd("4"),
			carbohydratesGrams = bd("5"),
			fatGrams = bd("2"),
		),
		defaultServing = DailyFoodDefaultServingSnapshot(bd("250"), DailyFoodSnapshotUnit.GRAM),
		conversions = DailyFoodConversionSnapshot(),
		nutritionSource = DailyNutritionSourceSnapshot(
			type = DailyFoodItemSourceType.USER_FOOD,
			originalSourceType = "PRODUCT_LABEL",
			estimated = false,
		),
		version = 2,
		updatedAt = Instant.parse("2026-07-30T10:00:00Z"),
	)

	private fun assertDecimal(expected: String, actual: BigDecimal?) {
		val value = assertIs<BigDecimal>(actual)
		assertEquals(0, bd(expected).compareTo(value))
	}

	private fun bd(value: String) = BigDecimal(value)
}
