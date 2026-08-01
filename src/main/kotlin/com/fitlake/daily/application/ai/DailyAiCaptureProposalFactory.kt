package com.fitlake.daily.application.ai

import com.fitlake.daily.application.port.DailyAiUserFoodMatchPort
import com.fitlake.daily.application.port.DailyAiUserFoodMatchResult
import com.fitlake.daily.application.port.DailyOwnedUserFood
import com.fitlake.daily.domain.capture.DailyCaptureEntry
import com.fitlake.daily.domain.capture.DailyCaptureEntryType
import com.fitlake.daily.domain.capture.DailyCapturePayload
import com.fitlake.daily.domain.capture.DailyCaptureType
import com.fitlake.daily.domain.capture.DailyEnteredQuantity
import com.fitlake.daily.domain.capture.DailyFoodCalculationException
import com.fitlake.daily.domain.capture.DailyFoodCaptureItem
import com.fitlake.daily.domain.capture.DailyFoodItemSourceType
import com.fitlake.daily.domain.capture.DailyFoodNutritionCalculator
import com.fitlake.daily.domain.capture.DailyFoodQuantityUnit
import com.fitlake.daily.domain.capture.DailyNutritionValues
import com.fitlake.daily.domain.capture.DailyResolvedFoodUnit
import com.fitlake.daily.domain.capture.DailyResolvedQuantity
import com.fitlake.daily.domain.capture.DailyScalarUnit
import com.fitlake.daily.domain.capture.FoodUnitNormalizer
import com.fitlake.user.domain.UserId
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.util.Locale
import java.util.UUID

@Component
class DailyAiCaptureProposalFactory(
	private val userFoodMatcher: DailyAiUserFoodMatchPort,
) {
	private val nutritionCalculator = DailyFoodNutritionCalculator()

	fun create(userId: UserId, proposal: AiCaptureProposal): DailyAiCaptureBuildResult = try {
		val proposedType = proposal.type?.trim()?.uppercase(Locale.ROOT)?.let(DailyCaptureType::valueOf)
			?: throw IllegalArgumentException("Capture type is required")
		val preparedMeals = proposal.meals.mapIndexed { mealIndex, meal ->
			require(meal.items.isNotEmpty()) { "A meal requires at least one food item" }
			require(meal.items.size <= 200) { "A meal cannot contain more than 200 food items" }
			PreparedMeal(
				mealName = meal.mealName.normalizedOrNull()?.also {
					require(it.length <= 100) { "Meal name must not exceed 100 characters" }
				},
				items = meal.items.mapIndexed { itemIndex, item -> item.prepare(mealIndex, itemIndex) },
			)
		}
		val fieldEntries = proposal.fields.toEntries()
		val noteEntry = proposal.note.normalizedOrNull()?.let { note ->
			DailyCaptureEntry(UUID.randomUUID(), DailyCaptureEntryType.NOTE, text = note)
		}
		validateStructure(proposedType, preparedMeals, fieldEntries, noteEntry)

		val resolutions = mutableListOf<DailyAiNutritionResolution>()
		val entries = buildList {
			preparedMeals.forEach { meal ->
				val items = meal.items.map { item ->
					val resolved = resolveFoodItem(userId, item)
					resolutions.add(resolved.resolution)
					resolved.item
				}
				add(
					DailyCaptureEntry(
						entryId = UUID.randomUUID(),
						type = DailyCaptureEntryType.FOOD,
						mealLabel = meal.mealName,
						items = items,
						nutritionTotal = DailyNutritionValues.strictTotal(
							items.map(DailyFoodCaptureItem::calculatedNutrition),
						),
					),
				)
			}
			addAll(fieldEntries)
			noteEntry?.let(::add)
		}
		val payload = DailyCapturePayload.fromEntries(entries)
		DailyAiCaptureBuildResult(payload, resolutions)
	} catch (exception: DailyAiInvalidOutputException) {
		throw exception
	} catch (exception: IllegalArgumentException) {
		throw DailyAiInvalidOutputException(exception)
	} catch (exception: ArithmeticException) {
		throw DailyAiInvalidOutputException(IllegalArgumentException("AI capture contains an invalid decimal value", exception))
	}

	private fun validateStructure(
		proposedType: DailyCaptureType,
		meals: List<PreparedMeal>,
		fieldEntries: List<DailyCaptureEntry>,
		noteEntry: DailyCaptureEntry?,
	) {
		val inferredType = when {
			noteEntry != null && (meals.isNotEmpty() || fieldEntries.isNotEmpty()) ->
				throw IllegalArgumentException("A note cannot be mixed with meals or daily fields")
			meals.isNotEmpty() && fieldEntries.isNotEmpty() -> DailyCaptureType.MIXED
			meals.isNotEmpty() -> DailyCaptureType.FOOD
			fieldEntries.isNotEmpty() -> DailyCaptureType.DAILY_FIELDS
			noteEntry != null -> DailyCaptureType.NOTE
			else -> throw IllegalArgumentException("Capture proposal contains no usable content")
		}
		require(proposedType == inferredType) {
			"Capture type $proposedType does not match the proposed content ($inferredType)"
		}
		require(meals.size + fieldEntries.size + (if (noteEntry == null) 0 else 1) <= 50) {
			"A capture cannot contain more than 50 entries"
		}
	}

	private fun AiFoodItemProposal.prepare(mealIndex: Int, itemIndex: Int): PreparedFoodItem {
		val name = foodName.normalizedRequired("Food name")
		require(name.length <= 255) { "Food name must not exceed 255 characters" }
		val amount = quantity ?: throw IllegalArgumentException("Food quantity is required")
		val normalizedUnit = FoodUnitNormalizer.normalize(unit.normalizedRequired("Food unit"))
		val entered = DailyEnteredQuantity(amount, normalizedUnit.toFoodQuantityUnit())
		val estimate = DailyNutritionValues(
			caloriesKcal = calories,
			proteinGrams = proteinG,
			carbohydratesGrams = carbsG,
			fatGrams = fatG,
		)
		require(calories <= MAX_AI_CALORIES) { "Estimated calories are outside the allowed range" }
		listOf(proteinG, carbsG, fatG).forEach { macro ->
			require(macro <= MAX_AI_MACRO_GRAMS) { "Estimated macronutrients are outside the allowed range" }
		}
		return PreparedFoodItem(mealIndex, itemIndex, name, entered, estimate)
	}

	private fun resolveFoodItem(userId: UserId, prepared: PreparedFoodItem): ResolvedFoodItem {
		return when (val match = userFoodMatcher.match(userId, prepared.foodName)) {
			is DailyAiUserFoodMatchResult.Unique -> resolveCatalogFood(prepared, match.food)
			DailyAiUserFoodMatchResult.None -> prepared.estimated(DailyAiNutritionResolutionOutcome.NO_MATCH)
			DailyAiUserFoodMatchResult.Ambiguous -> prepared.estimated(DailyAiNutritionResolutionOutcome.AMBIGUOUS_MATCH)
		}
	}

	private fun resolveCatalogFood(
		prepared: PreparedFoodItem,
		food: DailyOwnedUserFood,
	): ResolvedFoodItem {
		val snapshot = food.snapshot()
		if (!snapshot.nutrientsPerBasis.hasCoreNutrition()) {
			return prepared.estimated(DailyAiNutritionResolutionOutcome.INCOMPLETE_CATALOG_FOOD)
		}
		val enteredCandidates = buildList {
			if (
				prepared.enteredQuantity.unit == DailyFoodQuantityUnit.SERVING &&
				snapshot.defaultServing != null
			) {
				add(prepared.enteredQuantity.copy(unit = DailyFoodQuantityUnit.DEFAULT_SERVING))
			}
			add(prepared.enteredQuantity)
		}.distinct()
		val calculated = enteredCandidates.firstNotNullOfOrNull { entered ->
			try {
				entered to nutritionCalculator.calculate(entered, snapshot)
			} catch (_: DailyFoodCalculationException) {
				null
			}
		} ?: return prepared.estimated(DailyAiNutritionResolutionOutcome.UNUSABLE_CATALOG_CONVERSION)

		val (entered, result) = calculated
		val item = DailyFoodCaptureItem(
			itemId = UUID.randomUUID(),
			sourceType = DailyFoodItemSourceType.USER_FOOD,
			userFoodId = food.userFoodId,
			displayName = food.displayName,
			brand = food.brand,
			enteredQuantity = entered,
			resolvedQuantity = result.resolvedQuantity,
			userFoodSnapshot = snapshot,
			calculatedNutrition = result.calculatedNutrition,
		)
		return ResolvedFoodItem(
			item = item,
			resolution = prepared.resolution(
				outcome = DailyAiNutritionResolutionOutcome.CATALOG_MATCH,
				finalNutrition = item.calculatedNutrition,
				userFoodId = food.userFoodId,
			),
		)
	}

	private fun PreparedFoodItem.estimated(outcome: DailyAiNutritionResolutionOutcome): ResolvedFoodItem {
		val item = DailyFoodCaptureItem(
			itemId = UUID.randomUUID(),
			sourceType = DailyFoodItemSourceType.AI_ESTIMATE,
			userFoodId = null,
			displayName = foodName,
			brand = null,
			enteredQuantity = enteredQuantity,
			resolvedQuantity = enteredQuantity.toResolvedQuantity(),
			userFoodSnapshot = null,
			calculatedNutrition = aiEstimate,
		)
		return ResolvedFoodItem(item, resolution(outcome, aiEstimate, null))
	}

	private fun PreparedFoodItem.resolution(
		outcome: DailyAiNutritionResolutionOutcome,
		finalNutrition: DailyNutritionValues,
		userFoodId: UUID?,
	) = DailyAiNutritionResolution(
		mealIndex = mealIndex,
		itemIndex = itemIndex,
		outcome = outcome,
		userFoodId = userFoodId,
		aiEstimate = aiEstimate,
		finalNutrition = finalNutrition,
	)

	private fun AiDailyFieldsProposal.toEntries(): List<DailyCaptureEntry> = buildList {
		bodyWeightKg?.let { add(scalar(DailyCaptureEntryType.WEIGHT, it, DailyScalarUnit.KILOGRAM)) }
		sleepHours?.let { add(scalar(DailyCaptureEntryType.SLEEP, it, DailyScalarUnit.HOUR)) }
		stepsCount?.let { add(scalar(DailyCaptureEntryType.STEPS, it.toBigDecimal(), DailyScalarUnit.COUNT)) }
		hydrationLiters?.let { add(scalar(DailyCaptureEntryType.HYDRATION, it, DailyScalarUnit.LITER)) }
		caffeineMg?.let { add(scalar(DailyCaptureEntryType.CAFFEINE, it.toBigDecimal(), DailyScalarUnit.MILLIGRAM)) }
		moodLevel?.let { add(scalar(DailyCaptureEntryType.MOOD, it.toBigDecimal(), DailyScalarUnit.LEVEL)) }
		focusLevel?.let { add(scalar(DailyCaptureEntryType.FOCUS, it.toBigDecimal(), DailyScalarUnit.LEVEL)) }
		stressLevel?.let { add(scalar(DailyCaptureEntryType.STRESS, it.toBigDecimal(), DailyScalarUnit.LEVEL)) }
		dailyNotes.normalizedOrNull()?.let { notes ->
			add(DailyCaptureEntry(UUID.randomUUID(), DailyCaptureEntryType.DAILY_NOTES, text = notes))
		}
	}

	private fun scalar(type: DailyCaptureEntryType, value: BigDecimal, unit: DailyScalarUnit) =
		DailyCaptureEntry(UUID.randomUUID(), type, value = value, unit = unit)

	private fun DailyEnteredQuantity.toResolvedQuantity(): DailyResolvedQuantity = when (unit) {
		DailyFoodQuantityUnit.GRAM -> DailyResolvedQuantity(amount, DailyResolvedFoodUnit.GRAM)
		DailyFoodQuantityUnit.KILOGRAM -> DailyResolvedQuantity(amount.multiply(THOUSAND), DailyResolvedFoodUnit.GRAM)
		DailyFoodQuantityUnit.MILLILITER -> DailyResolvedQuantity(amount, DailyResolvedFoodUnit.MILLILITER)
		DailyFoodQuantityUnit.LITER -> DailyResolvedQuantity(amount.multiply(THOUSAND), DailyResolvedFoodUnit.MILLILITER)
		DailyFoodQuantityUnit.PIECE -> DailyResolvedQuantity(amount, DailyResolvedFoodUnit.PIECE)
		DailyFoodQuantityUnit.SERVING -> DailyResolvedQuantity(amount, DailyResolvedFoodUnit.SERVING)
		DailyFoodQuantityUnit.DEFAULT_SERVING -> error("AI fallback cannot contain DEFAULT_SERVING")
	}

	private fun DailyNutritionValues.hasCoreNutrition(): Boolean =
		caloriesKcal != null && proteinGrams != null && carbohydratesGrams != null && fatGrams != null

	private fun String?.normalizedRequired(label: String): String =
		normalizedOrNull() ?: throw IllegalArgumentException("$label is required")

	private fun String?.normalizedOrNull(): String? = this?.trim()?.takeIf(String::isNotEmpty)

	private fun String.toFoodQuantityUnit(): DailyFoodQuantityUnit = when (this) {
		"g" -> DailyFoodQuantityUnit.GRAM
		"kg" -> DailyFoodQuantityUnit.KILOGRAM
		"ml" -> DailyFoodQuantityUnit.MILLILITER
		"l" -> DailyFoodQuantityUnit.LITER
		"unit" -> DailyFoodQuantityUnit.PIECE
		"portion" -> DailyFoodQuantityUnit.SERVING
		else -> error("Unsupported normalized AI food unit: $this")
	}

	private data class PreparedMeal(
		val mealName: String?,
		val items: List<PreparedFoodItem>,
	)

	private data class PreparedFoodItem(
		val mealIndex: Int,
		val itemIndex: Int,
		val foodName: String,
		val enteredQuantity: DailyEnteredQuantity,
		val aiEstimate: DailyNutritionValues,
	)

	private data class ResolvedFoodItem(
		val item: DailyFoodCaptureItem,
		val resolution: DailyAiNutritionResolution,
	)

	companion object {
		private val THOUSAND = BigDecimal("1000")
		private val MAX_AI_CALORIES = BigDecimal("100000")
		private val MAX_AI_MACRO_GRAMS = BigDecimal("5000")
	}
}

data class DailyAiCaptureBuildResult(
	val payload: DailyCapturePayload,
	val nutritionResolutions: List<DailyAiNutritionResolution>,
)

enum class DailyAiNutritionResolutionOutcome {
	CATALOG_MATCH,
	NO_MATCH,
	AMBIGUOUS_MATCH,
	INCOMPLETE_CATALOG_FOOD,
	UNUSABLE_CATALOG_CONVERSION,
}

data class DailyAiNutritionResolution(
	val mealIndex: Int,
	val itemIndex: Int,
	val outcome: DailyAiNutritionResolutionOutcome,
	val userFoodId: UUID?,
	val aiEstimate: DailyNutritionValues,
	val finalNutrition: DailyNutritionValues,
) {
	fun toAuditMap(): Map<String, Any?> = linkedMapOf(
		"mealIndex" to mealIndex,
		"itemIndex" to itemIndex,
		"outcome" to outcome.name,
		"userFoodId" to userFoodId?.toString(),
		"aiEstimate" to aiEstimate.toAuditMap(),
		"finalNutrition" to finalNutrition.toAuditMap(),
	)

	private fun DailyNutritionValues.toAuditMap(): Map<String, Any?> = linkedMapOf(
		"caloriesKcal" to caloriesKcal?.toPlainString(),
		"proteinGrams" to proteinGrams?.toPlainString(),
		"carbohydratesGrams" to carbohydratesGrams?.toPlainString(),
		"fatGrams" to fatGrams?.toPlainString(),
	)
}
