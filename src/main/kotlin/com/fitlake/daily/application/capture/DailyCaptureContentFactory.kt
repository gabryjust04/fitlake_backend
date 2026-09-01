package com.fitlake.daily.application.capture

import com.fitlake.daily.application.DailyNotFoundException
import com.fitlake.daily.application.DailyValidationException
import com.fitlake.daily.application.port.DailyUserFoodLookupPort
import com.fitlake.daily.domain.capture.DailyCaptureEntry
import com.fitlake.daily.domain.capture.DailyCaptureEntryType
import com.fitlake.daily.domain.capture.DailyCapturePayload
import com.fitlake.daily.domain.capture.DailyEnteredQuantity
import com.fitlake.daily.domain.capture.DailyFoodCaptureItem
import com.fitlake.daily.domain.capture.DailyFoodItemSourceType
import com.fitlake.daily.domain.capture.DailyFoodNutritionCalculator
import com.fitlake.daily.domain.capture.DailyNutritionValues
import com.fitlake.daily.domain.capture.DailyScalarUnit
import com.fitlake.daily.domain.capture.normalizedDailyDecimal
import com.fitlake.user.domain.UserId
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.UUID

@Component
class DailyCaptureContentFactory(
	private val userFoodLookup: DailyUserFoodLookupPort,
) {
	private val nutritionCalculator = DailyFoodNutritionCalculator()

	fun create(userId: UserId, input: DailyCaptureContentInput): DailyCapturePayload =
		build(userId, null, input)

	fun replace(
		userId: UserId,
		existing: DailyCapturePayload,
		input: DailyCaptureContentInput,
	): DailyCapturePayload = build(userId, existing, input)

	private fun build(
		userId: UserId,
		existing: DailyCapturePayload?,
		input: DailyCaptureContentInput,
	): DailyCapturePayload = try {
		require(input.entries.isNotEmpty()) { "Capture content must contain at least one entry" }
		require(input.entries.size <= 50) { "Capture content cannot contain more than 50 entries" }
		ensureUnique(input.entries.mapNotNull(DailyCaptureEntryInput::entryId), "entry IDs")
		ensureUnique(
			input.entries.flatMap(DailyCaptureEntryInput::items).mapNotNull(DailyFoodItemInput::itemId),
			"food item IDs",
		)

		val existingEntries = existing?.entries.orEmpty().associateBy(DailyCaptureEntry::entryId)
		val existingItems = existing?.entries.orEmpty()
			.flatMap(DailyCaptureEntry::items)
			.associateBy(DailyFoodCaptureItem::itemId)
		val entries = input.entries.map { requested ->
			val previous = requested.entryId?.let { entryId ->
				existingEntries[entryId]
					?: throw IllegalArgumentException("Entry ID does not belong to the target capture")
			}
			if (previous != null) {
				require(previous.type == requested.type) { "An existing entry cannot change its type" }
			}
			val entryId = requested.entryId ?: UUID.randomUUID()
			when (requested.type) {
				DailyCaptureEntryType.FOOD -> foodEntry(userId, entryId, requested, existingItems)
				DailyCaptureEntryType.DAILY_NOTES,
				DailyCaptureEntryType.NOTE,
				-> textEntry(entryId, requested)
				else -> scalarEntry(entryId, requested)
			}
		}
		DailyCapturePayload.fromEntries(entries)
	} catch (exception: DailyNotFoundException) {
		throw exception
	} catch (exception: DailyValidationException) {
		throw exception
	} catch (exception: IllegalArgumentException) {
		throw DailyValidationException(exception.message ?: "Invalid capture content")
	} catch (exception: ArithmeticException) {
		throw DailyValidationException("Capture content contains an invalid decimal value")
	}

	private fun foodEntry(
		userId: UserId,
		entryId: UUID,
		input: DailyCaptureEntryInput,
		existingItems: Map<UUID, DailyFoodCaptureItem>,
	): DailyCaptureEntry {
		require(input.value == null && input.unit == null && input.text == null) {
			"A FOOD entry cannot contain scalar or text values"
		}
		require(input.items.isNotEmpty()) { "A FOOD entry requires at least one item" }
		val items = input.items.map { requested ->
			val oldItem = requested.itemId?.let { itemId ->
				existingItems[itemId]
					?: throw IllegalArgumentException("Food item ID does not belong to the target capture")
			}
			foodItem(userId, requested.itemId ?: UUID.randomUUID(), requested, oldItem)
		}
		return DailyCaptureEntry(
			entryId = entryId,
			type = DailyCaptureEntryType.FOOD,
			mealType = input.mealType,
			mealLabel = input.mealLabel.normalizedOrNull(),
			items = items,
			nutritionTotal = DailyNutritionValues.strictTotal(items.map(DailyFoodCaptureItem::calculatedNutrition)),
		)
	}

	private fun foodItem(
		userId: UserId,
		itemId: UUID,
		input: DailyFoodItemInput,
		previous: DailyFoodCaptureItem?,
	): DailyFoodCaptureItem {
		if (input.sourceType == DailyFoodItemSourceType.AI_ESTIMATE) {
			require(input.userFoodId == null) { "AI_ESTIMATE item cannot reference userFoodId" }
			val existing = previous
				?: throw IllegalArgumentException("AI-estimated items can only be preserved from the target capture")
			require(existing.sourceType == DailyFoodItemSourceType.AI_ESTIMATE) {
				"Food item source cannot be changed to AI_ESTIMATE"
			}
			val entered = DailyEnteredQuantity(input.quantity.amount, input.quantity.unit)
			require(existing.enteredQuantity.numericallyEquals(entered)) {
				"AI-estimated item quantity cannot be changed without AI reprocessing"
			}
			return existing
		}
		require(input.sourceType == DailyFoodItemSourceType.USER_FOOD) {
			"Typed manual captures currently accept USER_FOOD items only"
		}
		val userFoodId = input.userFoodId ?: throw IllegalArgumentException("USER_FOOD item requires userFoodId")
		val entered = DailyEnteredQuantity(input.quantity.amount, input.quantity.unit)

		if (
			previous != null &&
			previous.sourceType == DailyFoodItemSourceType.USER_FOOD &&
			previous.userFoodId == userFoodId
		) {
			if (previous.enteredQuantity.numericallyEquals(entered)) return previous
			val snapshot = previous.userFoodSnapshot
				?: throw IllegalArgumentException("Existing user-food item has no reusable nutrition snapshot")
			val recalculated = nutritionCalculator.calculate(entered, snapshot)
			return previous.copy(
				enteredQuantity = entered,
				resolvedQuantity = recalculated.resolvedQuantity,
				calculatedNutrition = recalculated.calculatedNutrition,
			)
		}

		val food = userFoodLookup.findActiveOwnedFood(userId, userFoodId)
			?: throw DailyNotFoundException("Personal food was not found")
		val snapshot = food.snapshot()
		val calculated = nutritionCalculator.calculate(entered, snapshot)
		return DailyFoodCaptureItem(
			itemId = itemId,
			sourceType = DailyFoodItemSourceType.USER_FOOD,
			userFoodId = food.userFoodId,
			displayName = food.displayName,
			brand = food.brand,
			enteredQuantity = entered,
			resolvedQuantity = calculated.resolvedQuantity,
			userFoodSnapshot = snapshot,
			calculatedNutrition = calculated.calculatedNutrition,
		)
	}

	private fun scalarEntry(entryId: UUID, input: DailyCaptureEntryInput): DailyCaptureEntry {
		require(input.items.isEmpty() && input.text == null && input.mealType == null && input.mealLabel == null) {
			"${input.type} entry contains incompatible fields"
		}
		val sourceValue = input.value ?: throw IllegalArgumentException("${input.type} entry requires value")
		val sourceUnit = input.unit ?: throw IllegalArgumentException("${input.type} entry requires unit")
		validateSourceDecimal(sourceValue)
		val (value, unit) = canonicalScalar(input.type, sourceValue, sourceUnit)
		return DailyCaptureEntry(entryId = entryId, type = input.type, value = value, unit = unit)
	}

	private fun textEntry(entryId: UUID, input: DailyCaptureEntryInput): DailyCaptureEntry {
		require(
			input.items.isEmpty() && input.value == null && input.unit == null &&
				input.mealType == null && input.mealLabel == null,
		) { "${input.type} entry contains incompatible fields" }
		return DailyCaptureEntry(
			entryId = entryId,
			type = input.type,
			text = input.text.normalizedOrNull()
				?: throw IllegalArgumentException("${input.type} entry requires text"),
		)
	}

	private fun canonicalScalar(
		type: DailyCaptureEntryType,
		value: BigDecimal,
		unit: DailyScalarUnit,
	): Pair<BigDecimal, DailyScalarUnit> = when (type) {
		DailyCaptureEntryType.WEIGHT -> when (unit) {
			DailyScalarUnit.KILOGRAM -> value to DailyScalarUnit.KILOGRAM
			DailyScalarUnit.GRAM -> value.divide(THOUSAND, DECIMAL_SCALE, RoundingMode.HALF_UP)
				.normalizedDailyDecimal() to DailyScalarUnit.KILOGRAM
			else -> incompatible(type, unit)
		}
		DailyCaptureEntryType.SLEEP -> compatible(type, value, unit, DailyScalarUnit.HOUR)
		DailyCaptureEntryType.STEPS -> compatible(type, value, unit, DailyScalarUnit.COUNT)
		DailyCaptureEntryType.HYDRATION -> when (unit) {
			DailyScalarUnit.LITER -> value to DailyScalarUnit.LITER
			DailyScalarUnit.MILLILITER -> value.divide(THOUSAND, DECIMAL_SCALE, RoundingMode.HALF_UP)
				.normalizedDailyDecimal() to DailyScalarUnit.LITER
			else -> incompatible(type, unit)
		}
		DailyCaptureEntryType.CAFFEINE -> compatible(type, value, unit, DailyScalarUnit.MILLIGRAM)
		DailyCaptureEntryType.MOOD,
		DailyCaptureEntryType.FOCUS,
		DailyCaptureEntryType.STRESS,
		-> compatible(type, value, unit, DailyScalarUnit.LEVEL)
		else -> throw IllegalArgumentException("$type is not a scalar entry")
	}

	private fun compatible(
		type: DailyCaptureEntryType,
		value: BigDecimal,
		actual: DailyScalarUnit,
		expected: DailyScalarUnit,
	): Pair<BigDecimal, DailyScalarUnit> {
		if (actual != expected) incompatible(type, actual)
		return value.normalizedDailyDecimal() to expected
	}

	private fun incompatible(type: DailyCaptureEntryType, unit: DailyScalarUnit): Nothing =
		throw IllegalArgumentException("Unit $unit is incompatible with $type")

	private fun validateSourceDecimal(value: BigDecimal) {
		require(value >= BigDecimal.ZERO) { "Entry value must not be negative" }
		require(value <= BigDecimal("1000000000000")) { "Entry value is too large" }
		require(value.stripTrailingZeros().scale().coerceAtLeast(0) <= DECIMAL_SCALE) {
			"Entry value must have at most $DECIMAL_SCALE decimal places"
		}
	}

	private fun ensureUnique(values: List<UUID>, label: String) {
		require(values.distinct().size == values.size) { "Duplicate $label are not allowed" }
	}

	private fun String?.normalizedOrNull(): String? = this?.trim()?.takeIf(String::isNotEmpty)

	companion object {
		private const val DECIMAL_SCALE = 6
		private val THOUSAND = BigDecimal("1000")
	}
}
