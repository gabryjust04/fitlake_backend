package com.fitlake.daily.application.ai

import com.fitlake.daily.application.port.DailyAiUserFoodMatchPort
import com.fitlake.daily.application.port.DailyAiUserFoodMatchResult
import com.fitlake.daily.application.port.DailyAiFoodMatchType
import com.fitlake.daily.application.port.DailyOwnedUserFood
import com.fitlake.daily.domain.capture.DailyCaptureEntryType
import com.fitlake.daily.domain.capture.DailyCaptureType
import com.fitlake.daily.domain.capture.DAILY_CAPTURE_SCHEMA_VERSION
import com.fitlake.daily.domain.capture.DailyFoodBasisSnapshot
import com.fitlake.daily.domain.capture.DailyFoodConversionSnapshot
import com.fitlake.daily.domain.capture.DailyFoodDefaultServingSnapshot
import com.fitlake.daily.domain.capture.DailyFoodItemSourceType
import com.fitlake.daily.domain.capture.DailyFoodQuantityUnit
import com.fitlake.daily.domain.capture.DailyFoodSnapshotUnit
import com.fitlake.daily.domain.capture.DailyNutritionSourceSnapshot
import com.fitlake.daily.domain.capture.DailyNutritionValues
import com.fitlake.daily.domain.capture.DailyResolvedFoodUnit
import com.fitlake.user.domain.UserId
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class DailyAiCaptureProposalFactoryTest {
	private val userId = UserId(UUID.fromString("10000000-0000-0000-0000-000000000001"))

	@Test
	fun `catalog match overrides its AI estimate while a miss keeps the exact estimate and totals include both`() {
		val matcher = FakeMatcher()
		val oats = food(
			id = UUID.fromString("20000000-0000-0000-0000-000000000001"),
			name = "Catalog oats",
			nutrients = nutrition("400", "10", "60", "8"),
		)
		matcher.returns(userId, "oats", DailyAiUserFoodMatchResult.Unique(oats))
		val factory = DailyAiCaptureProposalFactory(matcher)

		val result = factory.create(
			userId,
			foodProposal(
				items = listOf(
					item("oats", "50", "g", "999", "99", "99", "99"),
					item("apple", "100", "g", "52", "0.3", "14", "0.2"),
				),
			),
		)

		assertEquals(DailyCaptureType.FOOD, result.payload.type)
		val entry = result.payload.entries.single()
		assertEquals(DailyCaptureEntryType.FOOD, entry.type)
		val catalogItem = entry.items[0]
		assertEquals(DailyFoodItemSourceType.USER_FOOD, catalogItem.sourceType)
		assertEquals(oats.userFoodId, catalogItem.userFoodId)
		assertEquals("Catalog oats", catalogItem.displayName)
		assertEquals(oats.snapshot(), catalogItem.userFoodSnapshot)
		assertNutrition("200", "5", "30", "4", catalogItem.calculatedNutrition)

		val estimatedItem = entry.items[1]
		assertEquals(DailyFoodItemSourceType.AI_ESTIMATE, estimatedItem.sourceType)
		assertNull(estimatedItem.userFoodId)
		assertNull(estimatedItem.userFoodSnapshot)
		assertNutrition("52", "0.3", "14", "0.2", estimatedItem.calculatedNutrition)
		assertNutrition("252", "5.3", "44", "4.2", assertNotNull(entry.nutritionTotal))

		assertEquals(
			listOf(
				DailyAiNutritionResolutionOutcome.CATALOG_MATCH,
				DailyAiNutritionResolutionOutcome.NO_MATCH,
			),
			result.nutritionResolutions.map(DailyAiNutritionResolution::outcome),
		)
		assertEquals(listOf("oats", "apple"), matcher.calls.map(FakeMatcher.Call::extractedName))
	}

	@Test
	fun `ambiguous catalog result falls back to the complete AI estimate`() {
		val matcher = FakeMatcher().apply {
			returns(
				userId,
				"rice",
				DailyAiUserFoodMatchResult.Ambiguous(
					reason = "MATCH_MARGIN_TOO_SMALL",
					bestMatchedBy = DailyAiFoodMatchType.FUZZY_NAME,
					bestScore = 0.81,
					runnerUpScore = 0.80,
					candidateCount = 2,
				),
			)
		}

		val result = DailyAiCaptureProposalFactory(matcher).create(
			userId,
			foodProposal(listOf(item("rice", "80", "g", "104.5", "2.1", "22.7", "0.3"))),
		)

		val foodItem = result.payload.entries.single().items.single()
		assertEquals(DailyFoodItemSourceType.AI_ESTIMATE, foodItem.sourceType)
		assertNutrition("104.5", "2.1", "22.7", "0.3", foodItem.calculatedNutrition)
		val resolution = result.nutritionResolutions.single()
		assertEquals(DailyAiNutritionResolutionOutcome.AMBIGUOUS_MATCH, resolution.outcome)
		assertNull(resolution.userFoodId)
		assertEquals(DailyAiFoodMatchType.FUZZY_NAME, resolution.matchedBy)
		assertEquals(0.81, resolution.matchScore)
		assertEquals(0.80, resolution.runnerUpScore)
		assertEquals(2, resolution.candidateCount)
		assertEquals("MATCH_MARGIN_TOO_SMALL", resolution.matchReason)
		assertEquals(0.81, resolution.toAuditMap()["matchScore"])
	}

	@Test
	fun `partial catalog nutrition and incompatible conversion each fall back to their whole AI estimate`() {
		val matcher = FakeMatcher()
		matcher.returns(
			userId,
			"partial food",
			DailyAiUserFoodMatchResult.Unique(
				food(
					name = "Partial food",
					nutrients = DailyNutritionValues(
						caloriesKcal = bd("120"),
						proteinGrams = null,
						carbohydratesGrams = bd("20"),
						fatGrams = bd("3"),
					),
				),
			),
		)
		matcher.returns(
			userId,
			"volume food",
			DailyAiUserFoodMatchResult.Unique(
				food(
					name = "Volume food",
					basisUnit = DailyFoodSnapshotUnit.MILLILITER,
					nutrients = nutrition("40", "1", "7", "1"),
				),
			),
		)

		val result = DailyAiCaptureProposalFactory(matcher).create(
			userId,
			foodProposal(
				listOf(
					item("partial food", "100", "g", "130", "8", "21", "2"),
					item("volume food", "100", "g", "75", "4", "9", "2.5"),
				),
			),
		)

		val items = result.payload.entries.single().items
		items.forEach { foodItem ->
			assertEquals(DailyFoodItemSourceType.AI_ESTIMATE, foodItem.sourceType)
			assertNull(foodItem.userFoodId)
			assertNull(foodItem.userFoodSnapshot)
		}
		assertNutrition("130", "8", "21", "2", items[0].calculatedNutrition)
		assertNutrition("75", "4", "9", "2.5", items[1].calculatedNutrition)
		assertEquals(
			listOf(
				DailyAiNutritionResolutionOutcome.INCOMPLETE_CATALOG_FOOD,
				DailyAiNutritionResolutionOutcome.UNUSABLE_CATALOG_CONVERSION,
			),
			result.nutritionResolutions.map(DailyAiNutritionResolution::outcome),
		)
	}

	@Test
	fun `negative and over-bound estimates are rejected before the catalog matcher is invoked`() {
		val matcher = FakeMatcher()
		val factory = DailyAiCaptureProposalFactory(matcher)

		assertFailsWith<DailyAiInvalidOutputException> {
			factory.create(
				userId,
				foodProposal(listOf(item("invalid negative", "100", "g", "100", "-0.1", "20", "3"))),
			)
		}
		assertFailsWith<DailyAiInvalidOutputException> {
			factory.create(
				userId,
				foodProposal(listOf(item("invalid bound", "100", "g", "100000.000001", "10", "20", "3"))),
			)
		}

		assertEquals(emptyList(), matcher.calls)
	}

	@Test
	fun `incompatible mandatory estimate is rejected even when a catalog match could satisfy the stated quantity`() {
		val matcher = FakeMatcher().apply {
			returns(
				userId,
				"oats",
				DailyAiUserFoodMatchResult.Unique(
					food(name = "Catalog oats", nutrients = nutrition("370", "13", "60", "7")),
				),
			)
		}
		val interpretation = foodProposal(
			listOf(
				AiFoodInterpretation(
					originalFragment = "oats",
					searchText = "oats",
					statedQuantity = AiFoodQuantity(bd("40"), "g"),
					estimatedQuantity = AiFoodQuantity(bd("1"), "unit"),
					nutritionEstimate = AiNutritionEstimate(
						basis = AiFoodQuantity(bd("100"), "g"),
						caloriesKcal = bd("370"),
						proteinGrams = bd("13"),
						carbohydratesGrams = bd("60"),
						fatGrams = bd("7"),
					),
				),
			),
		)

		assertFailsWith<DailyAiInvalidOutputException> {
			DailyAiCaptureProposalFactory(matcher).create(userId, "oats", interpretation)
		}
		assertEquals(emptyList(), matcher.calls)
	}

	@Test
	fun `daily notes preserve the exact model fragment without trimming`() {
		val exactNote = "  mi sento molto bene  "
		val result = DailyAiCaptureProposalFactory(FakeMatcher()).create(
			userId = userId,
			rawText = "Nota:$exactNote",
			interpretation = DailyMessageInterpretation(
				outcome = DailyMessageInterpretationOutcome.COMPLETE,
				fields = listOf(
					AiDailyFieldInterpretation(
						field = AiDailyFieldType.DAILY_NOTES,
						textValue = exactNote,
						originalFragment = exactNote,
					),
				),
			),
		)

		assertEquals(exactNote, result.payload.entries.single().text)
		assertFailsWith<DailyAiInvalidOutputException> {
			DailyAiCaptureProposalFactory(FakeMatcher()).create(
				userId = userId,
				rawText = "Nota:$exactNote",
				interpretation = DailyMessageInterpretation(
					outcome = DailyMessageInterpretationOutcome.COMPLETE,
					fields = listOf(
						AiDailyFieldInterpretation(
							field = AiDailyFieldType.DAILY_NOTES,
							textValue = "testo inventato",
							originalFragment = exactNote,
						),
					),
				),
			)
		}
	}

	@Test
	fun `food plus weight derives one mixed payload`() {
		val result = DailyAiCaptureProposalFactory(FakeMatcher()).create(
			userId,
			DailyMessageInterpretation(
				outcome = DailyMessageInterpretationOutcome.COMPLETE,
				meals = listOf(
					AiMealInterpretation("breakfast", listOf(item("banana", "1", "unit", "105", "1.3", "27", "0.4"))),
				),
				fields = listOf(
					AiDailyFieldInterpretation(
						field = AiDailyFieldType.BODY_WEIGHT_KG,
						numericValue = bd("78.4"),
						originalFragment = "78.4",
					),
				),
			),
		)

		assertEquals(DailyCaptureType.MIXED, result.payload.type)
		assertEquals(
			setOf(DailyCaptureEntryType.FOOD, DailyCaptureEntryType.WEIGHT),
			result.payload.entries.map { it.type }.toSet(),
		)
		assertDecimal("78.4", result.payload.fields.bodyWeightKg)
		assertEquals(1, result.payload.meals.size)
		assertNutrition(
			"105",
			"1.3",
			"27",
			"0.4",
			result.payload.entries.single { it.type == DailyCaptureEntryType.FOOD }.items.single().calculatedNutrition,
		)
	}

	@Test
	fun `daily fields and note proposals both produce schema v2 entries`() {
		val factory = DailyAiCaptureProposalFactory(FakeMatcher())
		val fields = factory.create(
			userId,
			DailyMessageInterpretation(
				outcome = DailyMessageInterpretationOutcome.COMPLETE,
				fields = listOf(
					AiDailyFieldInterpretation(
						field = AiDailyFieldType.SLEEP_HOURS,
						numericValue = bd("7.5"),
						originalFragment = "7.5",
					),
				),
			),
		)
		val note = factory.create(
			userId = userId,
			rawText = "Giornata tranquilla",
			interpretation = DailyMessageInterpretation(DailyMessageInterpretationOutcome.UNRESOLVED),
		)

		assertEquals(DAILY_CAPTURE_SCHEMA_VERSION, fields.payload.schemaVersion)
		assertEquals(DailyCaptureEntryType.SLEEP, fields.payload.entries.single().type)
		assertEquals(emptyList(), fields.nutritionResolutions)
		assertEquals(DAILY_CAPTURE_SCHEMA_VERSION, note.payload.schemaVersion)
		assertEquals(DailyCaptureEntryType.NOTE, note.payload.entries.single().type)
		assertEquals("Giornata tranquilla", note.payload.entries.single().text)
		assertEquals(emptyList(), note.nutritionResolutions)
	}

	@Test
	fun `explicit portion is preserved while catalog default serving resolves its nutrition`() {
		val matcher = FakeMatcher()
		val yogurt = food(
			name = "Large yogurt",
			nutrients = nutrition("80", "10", "4", "2"),
			defaultServing = DailyFoodDefaultServingSnapshot(bd("250"), DailyFoodSnapshotUnit.GRAM),
		)
		matcher.returns(userId, "yogurt", DailyAiUserFoodMatchResult.Unique(yogurt))

		val result = DailyAiCaptureProposalFactory(matcher).create(
			userId,
			foodProposal(listOf(item("yogurt", "2", "portion", "150", "12", "8", "3"))),
		)

		val foodItem = result.payload.entries.single().items.single()
		assertEquals(DailyFoodItemSourceType.USER_FOOD, foodItem.sourceType)
		assertEquals(DailyFoodQuantityUnit.SERVING, foodItem.enteredQuantity.unit)
		assertDecimal("2", foodItem.enteredQuantity.amount)
		assertEquals(DailyResolvedFoodUnit.GRAM, foodItem.resolvedQuantity.unit)
		assertDecimal("500", foodItem.resolvedQuantity.amount)
		assertNutrition("400", "50", "20", "10", foodItem.calculatedNutrition)
		assertEquals(DailyAiNutritionResolutionOutcome.CATALOG_MATCH, result.nutritionResolutions.single().outcome)
	}

	private fun foodProposal(items: List<AiFoodInterpretation>) = DailyMessageInterpretation(
		outcome = DailyMessageInterpretationOutcome.COMPLETE,
		meals = listOf(AiMealInterpretation(mealName = "meal", items = items)),
	)

	private fun item(
		name: String,
		quantity: String,
		unit: String,
		calories: String,
		protein: String,
		carbs: String,
		fat: String,
	) = AiFoodInterpretation(
		originalFragment = name,
		searchText = name,
		statedQuantity = AiFoodQuantity(bd(quantity), unit),
		estimatedQuantity = AiFoodQuantity(bd(quantity), unit),
		nutritionEstimate = AiNutritionEstimate(
			basis = AiFoodQuantity(bd(quantity), unit),
			caloriesKcal = bd(calories),
			proteinGrams = bd(protein),
			carbohydratesGrams = bd(carbs),
			fatGrams = bd(fat),
		),
	)

	private fun DailyAiCaptureProposalFactory.create(
		userId: UserId,
		interpretation: DailyMessageInterpretation,
	): DailyAiCaptureBuildResult {
		val fragments = buildList {
			interpretation.meals.flatMap(AiMealInterpretation::items).map(AiFoodInterpretation::originalFragment).forEach(::add)
			interpretation.fields.map(AiDailyFieldInterpretation::originalFragment).forEach(::add)
		}
		return create(userId, fragments.joinToString(" e "), interpretation)
	}

	private fun food(
		id: UUID = UUID.randomUUID(),
		name: String,
		basisUnit: DailyFoodSnapshotUnit = DailyFoodSnapshotUnit.GRAM,
		nutrients: DailyNutritionValues,
		defaultServing: DailyFoodDefaultServingSnapshot? = null,
		conversions: DailyFoodConversionSnapshot = DailyFoodConversionSnapshot(),
	) = DailyOwnedUserFood(
		userFoodId = id,
		displayName = name,
		brand = "Test brand",
		nutritionBasis = DailyFoodBasisSnapshot(bd("100"), basisUnit),
		nutrientsPerBasis = nutrients,
		defaultServing = defaultServing,
		conversions = conversions,
		nutritionSource = DailyNutritionSourceSnapshot(
			type = DailyFoodItemSourceType.USER_FOOD,
			originalSourceType = "PRODUCT_LABEL",
			estimated = false,
		),
		version = 3,
		updatedAt = Instant.parse("2026-07-30T10:00:00Z"),
	)

	private fun nutrition(calories: String, protein: String, carbs: String, fat: String) = DailyNutritionValues(
		caloriesKcal = bd(calories),
		proteinGrams = bd(protein),
		carbohydratesGrams = bd(carbs),
		fatGrams = bd(fat),
	)

	private fun assertNutrition(
		calories: String,
		protein: String,
		carbs: String,
		fat: String,
		actual: DailyNutritionValues,
	) {
		assertDecimal(calories, actual.caloriesKcal)
		assertDecimal(protein, actual.proteinGrams)
		assertDecimal(carbs, actual.carbohydratesGrams)
		assertDecimal(fat, actual.fatGrams)
	}

	private fun assertDecimal(expected: String, actual: BigDecimal?) {
		assertNotNull(actual)
		assertEquals(0, bd(expected).compareTo(actual), "Expected $expected but was $actual")
	}

	private fun bd(value: String) = BigDecimal(value)

	private class FakeMatcher : DailyAiUserFoodMatchPort {
		data class Call(val userId: UserId, val extractedName: String)

		val calls = mutableListOf<Call>()
		private val answers = mutableMapOf<Pair<UserId, String>, DailyAiUserFoodMatchResult>()

		fun returns(userId: UserId, extractedName: String, result: DailyAiUserFoodMatchResult) {
			answers[userId to extractedName] = result
		}

		override fun match(userId: UserId, searchText: String): DailyAiUserFoodMatchResult {
			calls += Call(userId, searchText)
			return answers[userId to searchText] ?: DailyAiUserFoodMatchResult.None
		}
	}
}
