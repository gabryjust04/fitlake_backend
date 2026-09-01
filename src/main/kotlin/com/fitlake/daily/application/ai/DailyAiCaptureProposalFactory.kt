package com.fitlake.daily.application.ai

import com.fitlake.daily.application.port.DailyAiUserFoodMatchPort
import com.fitlake.daily.application.port.DailyAiUserFoodMatchResult
import com.fitlake.daily.application.port.DailyAiFoodMatchType
import com.fitlake.daily.application.port.DailyOwnedUserFood
import com.fitlake.daily.domain.capture.DailyCaptureEntry
import com.fitlake.daily.domain.capture.DailyCaptureEntryType
import com.fitlake.daily.domain.capture.DailyCapturePayload
import com.fitlake.daily.domain.capture.DailyEnteredQuantity
import com.fitlake.daily.domain.capture.DailyFoodBasisSnapshot
import com.fitlake.daily.domain.capture.DailyFoodCalculation
import com.fitlake.daily.domain.capture.DailyFoodCalculationException
import com.fitlake.daily.domain.capture.DailyFoodCaptureItem
import com.fitlake.daily.domain.capture.DailyFoodItemSourceType
import com.fitlake.daily.domain.capture.DailyFoodNutritionCalculator
import com.fitlake.daily.domain.capture.DailyFoodQuantityUnit
import com.fitlake.daily.domain.capture.DailyFoodSnapshotUnit
import com.fitlake.daily.domain.capture.DailyNutritionValues
import com.fitlake.daily.domain.capture.DailyScalarUnit
import com.fitlake.daily.domain.capture.FoodUnitNormalizer
import com.fitlake.user.domain.UserId
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.util.UUID

@Component
class DailyAiCaptureProposalFactory(
	private val userFoodMatcher: DailyAiUserFoodMatchPort,
) {
	private val nutritionCalculator = DailyFoodNutritionCalculator()

	fun create(
		userId: UserId,
		rawText: String,
		interpretation: DailyMessageInterpretation,
	): DailyAiCaptureBuildResult = try {
		validateInterpretation(rawText, interpretation)
		val resolutions = mutableListOf<DailyAiNutritionResolution>()
		val entries = buildList {
			interpretation.meals.forEachIndexed { mealIndex, meal ->
				require(meal.items.isNotEmpty()) { "An interpreted meal requires at least one food item" }
				val mealName = meal.mealName.normalizedOrNull()?.also {
					require(it.length <= 100) { "Meal name must not exceed 100 characters" }
				}
				val items = meal.items.mapIndexed { itemIndex, proposal ->
					val prepared = proposal.prepare(rawText, mealIndex, itemIndex)
					resolveFoodItem(userId, prepared).also { resolutions += it.resolution }.item
				}
				add(
					DailyCaptureEntry(
						entryId = UUID.randomUUID(),
						type = DailyCaptureEntryType.FOOD,
						mealLabel = mealName,
						items = items,
						nutritionTotal = DailyNutritionValues.strictTotal(
							items.map(DailyFoodCaptureItem::calculatedNutrition),
						),
					),
				)
			}
			addAll(interpretation.fields.map { it.toEntry(rawText) })
			when (interpretation.outcome) {
				DailyMessageInterpretationOutcome.PARTIAL -> interpretation.unresolvedFragments.forEach { fragment ->
					add(DailyCaptureEntry(UUID.randomUUID(), DailyCaptureEntryType.NOTE, text = fragment))
				}
				DailyMessageInterpretationOutcome.UNRESOLVED ->
					add(DailyCaptureEntry(UUID.randomUUID(), DailyCaptureEntryType.NOTE, text = rawText))
				else -> Unit
			}
		}
		DailyAiCaptureBuildResult(
			payload = DailyCapturePayload.fromEntries(entries),
			nutritionResolutions = resolutions,
			interpretationOutcome = interpretation.outcome,
		)
	} catch (exception: DailyAiInvalidOutputException) {
		throw exception
	} catch (exception: IllegalArgumentException) {
		throw DailyAiInvalidOutputException(exception)
	} catch (exception: ArithmeticException) {
		throw DailyAiInvalidOutputException(
			IllegalArgumentException("AI interpretation contains an invalid decimal value", exception),
		)
	}

	private fun validateInterpretation(rawText: String, interpretation: DailyMessageInterpretation) {
		require(rawText.isNotBlank()) { "Raw message must not be blank" }
		interpretation.confidence?.let {
			require(it in BigDecimal.ZERO..BigDecimal.ONE) { "Confidence is outside the allowed range" }
		}
		val structuredCount = interpretation.meals.sumOf { it.items.size } + interpretation.fields.size
		val unresolved = interpretation.unresolvedFragments
		require(unresolved.size <= 50) { "Too many unresolved fragments" }
		require(unresolved.distinct().size == unresolved.size) { "Unresolved fragments must be unique" }
		unresolved.forEach { it.requireExactFragment(rawText, "Unresolved fragment") }

		when (interpretation.outcome) {
			DailyMessageInterpretationOutcome.COMPLETE -> {
				require(structuredCount > 0) { "COMPLETE requires structured Daily data" }
				require(unresolved.isEmpty()) { "COMPLETE cannot contain unresolved fragments" }
			}
			DailyMessageInterpretationOutcome.PARTIAL -> {
				require(structuredCount > 0) { "PARTIAL requires structured Daily data" }
				require(unresolved.isNotEmpty()) { "PARTIAL requires unresolved fragments" }
			}
			DailyMessageInterpretationOutcome.UNRESOLVED -> {
				require(structuredCount == 0) { "UNRESOLVED cannot contain structured Daily data" }
				require(unresolved.isEmpty() || unresolved == listOf(rawText)) {
					"UNRESOLVED must leave the original message untouched"
				}
			}
			DailyMessageInterpretationOutcome.NO_RELEVANT_DATA ->
				throw IllegalArgumentException("NO_RELEVANT_DATA does not create a capture")
		}
	}

	private fun AiFoodInterpretation.prepare(
		rawText: String,
		mealIndex: Int,
		itemIndex: Int,
	): PreparedFoodItem {
		val fragment = originalFragment.requireExactFragment(rawText, "Food original fragment")
		val search = searchText.normalizedRequired("Food search text")
		require(search.length <= 255) { "Food search text must not exceed 255 characters" }
		require(assumptions.size <= 20) { "A food item cannot contain more than 20 assumptions" }
		assumptions.forEach { assumption ->
			require(assumption.isNotBlank() && assumption.length <= 500) { "AI assumptions must be concise and non-blank" }
		}
		val stated = statedQuantity?.toEnteredQuantity()
		val estimated = estimatedQuantity.toEnteredQuantity()
		val estimateBasis = nutritionEstimate.basis.toBasisSnapshot()
		val estimate = DailyNutritionValues(
			caloriesKcal = nutritionEstimate.caloriesKcal,
			proteinGrams = nutritionEstimate.proteinGrams,
			carbohydratesGrams = nutritionEstimate.carbohydratesGrams,
			fatGrams = nutritionEstimate.fatGrams,
			fiberGrams = nutritionEstimate.fiberGrams,
			sugarsGrams = nutritionEstimate.sugarsGrams,
			saturatedFatGrams = nutritionEstimate.saturatedFatGrams,
			sodiumMilligrams = nutritionEstimate.sodiumMilligrams,
			saltGrams = nutritionEstimate.saltGrams,
		)
		require(estimate.hasCoreNutrition()) { "AI estimate requires calories and all core macronutrients" }
		require(nutritionEstimate.caloriesKcal <= MAX_AI_CALORIES) { "Estimated calories are outside the allowed range" }
		listOf(
			nutritionEstimate.proteinGrams,
			nutritionEstimate.carbohydratesGrams,
			nutritionEstimate.fatGrams,
		).forEach { require(it <= MAX_AI_MACRO_GRAMS) { "Estimated macronutrients are outside the allowed range" } }
		try {
			nutritionCalculator.calculate(estimated, estimateBasis, estimate)
		} catch (exception: DailyFoodCalculationException) {
			throw IllegalArgumentException(
				"AI estimated quantity cannot be scaled from its nutrition basis",
				exception,
			)
		}

		return PreparedFoodItem(
			mealIndex = mealIndex,
			itemIndex = itemIndex,
			originalFragment = fragment,
			searchText = search,
			statedQuantity = stated,
			estimatedQuantity = estimated,
			estimateBasis = estimateBasis,
			aiEstimatePerBasis = estimate,
			assumptionCount = assumptions.size,
		)
	}

	private fun resolveFoodItem(userId: UserId, prepared: PreparedFoodItem): ResolvedFoodItem =
		when (val match = userFoodMatcher.match(userId, prepared.searchText)) {
			is DailyAiUserFoodMatchResult.Unique -> resolveCatalogFood(prepared, match)
			DailyAiUserFoodMatchResult.None -> prepared.estimated(DailyAiNutritionResolutionOutcome.NO_MATCH)
			is DailyAiUserFoodMatchResult.Ambiguous -> prepared.estimated(
				DailyAiNutritionResolutionOutcome.AMBIGUOUS_MATCH,
				ambiguousMatch = match,
			)
		}

	private fun resolveCatalogFood(
		prepared: PreparedFoodItem,
		match: DailyAiUserFoodMatchResult.Unique,
	): ResolvedFoodItem {
		val food = match.food
		val snapshot = food.snapshot()
		if (!snapshot.nutrientsPerBasis.hasCoreNutrition()) {
			return prepared.estimated(
				DailyAiNutritionResolutionOutcome.INCOMPLETE_CATALOG_FOOD,
				match = match,
			)
		}
		val quantityChoice = when {
			prepared.statedQuantity != null -> QuantityChoice(prepared.statedQuantity, DailyAiQuantitySource.STATED)
			snapshot.defaultServing != null -> QuantityChoice(
				DailyEnteredQuantity(BigDecimal.ONE, DailyFoodQuantityUnit.DEFAULT_SERVING),
				DailyAiQuantitySource.MATCHED_DEFAULT_SERVING,
			)
			else -> QuantityChoice(prepared.estimatedQuantity, DailyAiQuantitySource.AI_ESTIMATED)
		}
		val calculated = try {
			nutritionCalculator.calculate(quantityChoice.quantity, snapshot)
		} catch (_: DailyFoodCalculationException) {
			return prepared.estimated(
				DailyAiNutritionResolutionOutcome.UNUSABLE_CATALOG_CONVERSION,
				match = match,
			)
		}
		val item = DailyFoodCaptureItem(
			itemId = UUID.randomUUID(),
			sourceType = DailyFoodItemSourceType.USER_FOOD,
			userFoodId = food.userFoodId,
			displayName = food.displayName,
			brand = food.brand,
			enteredQuantity = quantityChoice.quantity,
			resolvedQuantity = calculated.resolvedQuantity,
			userFoodSnapshot = snapshot,
			calculatedNutrition = calculated.calculatedNutrition,
		)
		return ResolvedFoodItem(
			item,
			prepared.resolution(
				outcome = DailyAiNutritionResolutionOutcome.CATALOG_MATCH,
				quantitySource = quantityChoice.source,
				match = match,
			),
		)
	}

	private fun PreparedFoodItem.estimated(
		outcome: DailyAiNutritionResolutionOutcome,
		match: DailyAiUserFoodMatchResult.Unique? = null,
		ambiguousMatch: DailyAiUserFoodMatchResult.Ambiguous? = null,
	): ResolvedFoodItem {
		val preferred = statedQuantity?.let { QuantityChoice(it, DailyAiQuantitySource.STATED) }
		val calculatedPreferred = preferred?.calculateEstimateOrNull(this)
		val selected = if (preferred != null && calculatedPreferred != null) {
			preferred to calculatedPreferred
		} else {
			val estimated = QuantityChoice(estimatedQuantity, DailyAiQuantitySource.AI_ESTIMATED)
			estimated to estimated.calculateEstimate(this)
		}
		val (choice, calculated) = selected
		val item = DailyFoodCaptureItem(
			itemId = UUID.randomUUID(),
			sourceType = DailyFoodItemSourceType.AI_ESTIMATE,
			userFoodId = null,
			displayName = searchText,
			brand = null,
			enteredQuantity = choice.quantity,
			resolvedQuantity = calculated.resolvedQuantity,
			userFoodSnapshot = null,
			calculatedNutrition = calculated.calculatedNutrition,
		)
		return ResolvedFoodItem(
			item,
			resolution(outcome, choice.source, match, ambiguousMatch),
		)
	}

	private fun QuantityChoice.calculateEstimateOrNull(prepared: PreparedFoodItem): DailyFoodCalculation? = try {
		calculateEstimate(prepared)
	} catch (_: DailyFoodCalculationException) {
		null
	}

	private fun QuantityChoice.calculateEstimate(prepared: PreparedFoodItem): DailyFoodCalculation =
		nutritionCalculator.calculate(
			enteredQuantity = quantity,
			nutritionBasis = prepared.estimateBasis,
			nutrientsPerBasis = prepared.aiEstimatePerBasis,
		)

	private fun PreparedFoodItem.resolution(
		outcome: DailyAiNutritionResolutionOutcome,
		quantitySource: DailyAiQuantitySource,
		match: DailyAiUserFoodMatchResult.Unique?,
		ambiguousMatch: DailyAiUserFoodMatchResult.Ambiguous? = null,
	) = DailyAiNutritionResolution(
		mealIndex = mealIndex,
		itemIndex = itemIndex,
		outcome = outcome,
		quantitySource = quantitySource,
		userFoodId = match?.food?.userFoodId,
		matchedBy = match?.matchedBy ?: ambiguousMatch?.bestMatchedBy,
		matchScore = match?.score ?: ambiguousMatch?.bestScore,
		runnerUpScore = ambiguousMatch?.runnerUpScore,
		candidateCount = ambiguousMatch?.candidateCount,
		matchReason = ambiguousMatch?.reason,
		assumptionCount = assumptionCount,
	)

	private fun AiDailyFieldInterpretation.toEntry(rawText: String): DailyCaptureEntry {
		originalFragment.requireExactFragment(rawText, "Daily field original fragment")
		return when (field) {
			AiDailyFieldType.BODY_WEIGHT_KG -> scalar(DailyCaptureEntryType.WEIGHT, requiredNumeric(), DailyScalarUnit.KILOGRAM)
			AiDailyFieldType.SLEEP_HOURS -> scalar(DailyCaptureEntryType.SLEEP, requiredNumeric(), DailyScalarUnit.HOUR)
			AiDailyFieldType.STEPS_COUNT -> scalar(DailyCaptureEntryType.STEPS, requiredInteger(), DailyScalarUnit.COUNT)
			AiDailyFieldType.HYDRATION_LITERS -> scalar(DailyCaptureEntryType.HYDRATION, requiredNumeric(), DailyScalarUnit.LITER)
			AiDailyFieldType.CAFFEINE_MG -> scalar(DailyCaptureEntryType.CAFFEINE, requiredInteger(), DailyScalarUnit.MILLIGRAM)
			AiDailyFieldType.MOOD_LEVEL -> scalar(DailyCaptureEntryType.MOOD, requiredInteger(), DailyScalarUnit.LEVEL)
			AiDailyFieldType.FOCUS_LEVEL -> scalar(DailyCaptureEntryType.FOCUS, requiredInteger(), DailyScalarUnit.LEVEL)
			AiDailyFieldType.STRESS_LEVEL -> scalar(DailyCaptureEntryType.STRESS, requiredInteger(), DailyScalarUnit.LEVEL)
			AiDailyFieldType.DAILY_NOTES -> {
				require(numericValue == null) { "DAILY_NOTES cannot contain a numeric value" }
				val text = textValue ?: throw IllegalArgumentException("Daily notes are required")
				require(text.isNotBlank()) { "Daily notes must not be blank" }
				require(text.length <= 10_000) { "Daily notes must not exceed 10000 characters" }
				text.requireExactFragment(rawText, "Daily notes text")
				DailyCaptureEntry(UUID.randomUUID(), DailyCaptureEntryType.DAILY_NOTES, text = text)
			}
		}
	}

	private fun AiDailyFieldInterpretation.requiredNumeric(): BigDecimal {
		require(textValue == null) { "$field cannot contain a text value" }
		return numericValue ?: throw IllegalArgumentException("$field requires a numeric value")
	}

	private fun AiDailyFieldInterpretation.requiredInteger(): BigDecimal {
		val value = requiredNumeric()
		require(value.stripTrailingZeros().scale() <= 0) { "$field requires an integer" }
		return value
	}

	private fun scalar(type: DailyCaptureEntryType, value: BigDecimal, unit: DailyScalarUnit) =
		DailyCaptureEntry(UUID.randomUUID(), type, value = value, unit = unit)

	private fun AiFoodQuantity.toEnteredQuantity(): DailyEnteredQuantity {
		val normalized = FoodUnitNormalizer.normalize(unit)
		return DailyEnteredQuantity(amount, normalized.toFoodQuantityUnit())
	}

	private fun AiFoodQuantity.toBasisSnapshot(): DailyFoodBasisSnapshot {
		val entered = toEnteredQuantity()
		require(entered.unit != DailyFoodQuantityUnit.DEFAULT_SERVING) { "AI nutrition basis cannot use a default serving" }
		return DailyFoodBasisSnapshot(entered.amount, entered.unit.toSnapshotUnit())
	}

	private fun String.toFoodQuantityUnit(): DailyFoodQuantityUnit = when (this) {
		"g" -> DailyFoodQuantityUnit.GRAM
		"kg" -> DailyFoodQuantityUnit.KILOGRAM
		"ml" -> DailyFoodQuantityUnit.MILLILITER
		"l" -> DailyFoodQuantityUnit.LITER
		"unit" -> DailyFoodQuantityUnit.PIECE
		"portion" -> DailyFoodQuantityUnit.SERVING
		else -> error("Unsupported normalized AI food unit: $this")
	}

	private fun DailyFoodQuantityUnit.toSnapshotUnit(): DailyFoodSnapshotUnit = when (this) {
		DailyFoodQuantityUnit.GRAM -> DailyFoodSnapshotUnit.GRAM
		DailyFoodQuantityUnit.KILOGRAM -> DailyFoodSnapshotUnit.KILOGRAM
		DailyFoodQuantityUnit.MILLILITER -> DailyFoodSnapshotUnit.MILLILITER
		DailyFoodQuantityUnit.LITER -> DailyFoodSnapshotUnit.LITER
		DailyFoodQuantityUnit.PIECE -> DailyFoodSnapshotUnit.PIECE
		DailyFoodQuantityUnit.SERVING -> DailyFoodSnapshotUnit.SERVING
		DailyFoodQuantityUnit.DEFAULT_SERVING -> error("Default serving cannot be used as an AI basis")
	}

	private fun DailyNutritionValues.hasCoreNutrition(): Boolean =
		caloriesKcal != null && proteinGrams != null && carbohydratesGrams != null && fatGrams != null

	private fun String.requireExactFragment(rawText: String, label: String): String {
		require(isNotBlank()) { "$label must not be blank" }
		require(length <= rawText.length && rawText.contains(this)) { "$label must be copied exactly from the input" }
		return this
	}

	private fun String?.normalizedRequired(label: String): String =
		normalizedOrNull() ?: throw IllegalArgumentException("$label is required")

	private fun String?.normalizedOrNull(): String? = this?.trim()?.takeIf(String::isNotEmpty)

	private data class PreparedFoodItem(
		val mealIndex: Int,
		val itemIndex: Int,
		val originalFragment: String,
		val searchText: String,
		val statedQuantity: DailyEnteredQuantity?,
		val estimatedQuantity: DailyEnteredQuantity,
		val estimateBasis: DailyFoodBasisSnapshot,
		val aiEstimatePerBasis: DailyNutritionValues,
		val assumptionCount: Int,
	) {
	}

	private data class QuantityChoice(
		val quantity: DailyEnteredQuantity,
		val source: DailyAiQuantitySource,
	)

	private data class ResolvedFoodItem(
		val item: DailyFoodCaptureItem,
		val resolution: DailyAiNutritionResolution,
	)

	companion object {
		private val MAX_AI_CALORIES = BigDecimal("100000")
		private val MAX_AI_MACRO_GRAMS = BigDecimal("5000")
	}
}

data class DailyAiCaptureBuildResult(
	val payload: DailyCapturePayload,
	val nutritionResolutions: List<DailyAiNutritionResolution>,
	val interpretationOutcome: DailyMessageInterpretationOutcome,
)

enum class DailyAiNutritionResolutionOutcome {
	CATALOG_MATCH,
	NO_MATCH,
	AMBIGUOUS_MATCH,
	INCOMPLETE_CATALOG_FOOD,
	UNUSABLE_CATALOG_CONVERSION,
}

enum class DailyAiQuantitySource {
	STATED,
	MATCHED_DEFAULT_SERVING,
	AI_ESTIMATED,
}

data class DailyAiNutritionResolution(
	val mealIndex: Int,
	val itemIndex: Int,
	val outcome: DailyAiNutritionResolutionOutcome,
	val quantitySource: DailyAiQuantitySource,
	val userFoodId: UUID?,
	val matchedBy: DailyAiFoodMatchType?,
	val matchScore: Double?,
	val runnerUpScore: Double?,
	val candidateCount: Int?,
	val matchReason: String?,
	val assumptionCount: Int,
) {
	fun toAuditMap(): Map<String, Any?> = linkedMapOf(
		"mealIndex" to mealIndex,
		"itemIndex" to itemIndex,
		"outcome" to outcome.name,
		"quantitySource" to quantitySource.name,
		"userFoodId" to userFoodId?.toString(),
		"matchedBy" to matchedBy?.name,
		"matchScore" to matchScore,
		"runnerUpScore" to runnerUpScore,
		"candidateCount" to candidateCount,
		"matchReason" to matchReason,
		"assumptionCount" to assumptionCount,
	)
}
