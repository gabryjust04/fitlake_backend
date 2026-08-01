package com.fitlake.daily.application.capture

import com.fitlake.daily.domain.capture.DAILY_CAPTURE_SCHEMA_VERSION
import com.fitlake.daily.domain.capture.DailyCaptureEntry
import com.fitlake.daily.domain.capture.DailyCaptureEntryType
import com.fitlake.daily.domain.capture.DailyCapturePayload
import com.fitlake.daily.domain.capture.DailyEnteredQuantity
import com.fitlake.daily.domain.capture.DailyFoodBasisSnapshot
import com.fitlake.daily.domain.capture.DailyFoodCaptureItem
import com.fitlake.daily.domain.capture.DailyFoodConversionSnapshot
import com.fitlake.daily.domain.capture.DailyFoodDefaultServingSnapshot
import com.fitlake.daily.domain.capture.DailyFoodItemSourceType
import com.fitlake.daily.domain.capture.DailyFoodQuantityUnit
import com.fitlake.daily.domain.capture.DailyFoodSnapshotUnit
import com.fitlake.daily.domain.capture.DailyMealType
import com.fitlake.daily.domain.capture.DailyNutritionSourceSnapshot
import com.fitlake.daily.domain.capture.DailyNutritionValues
import com.fitlake.daily.domain.capture.DailyResolvedFoodUnit
import com.fitlake.daily.domain.capture.DailyResolvedQuantity
import com.fitlake.daily.domain.capture.DailyScalarUnit
import com.fitlake.daily.domain.capture.DailyUserFoodSnapshot
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * Canonical JSON-compatible map codec for the JSONB capture payload and immutable audit snapshots.
 *
 * Version 2 stores only its authoritative typed entries, from which the internal metrics
 * projection is deterministically rebuilt while decoding. Decimal
 * values are stored as canonical decimal strings because Hibernate's untyped JSON map mapper would
 * otherwise deserialize large JSON numbers through binary floating point and lose precision.
 */
object DailyCapturePayloadCodec {
	fun encode(payload: DailyCapturePayload): Map<String, Any?> {
		if (payload.schemaVersion != DAILY_CAPTURE_SCHEMA_VERSION) unsupportedVersion(payload.schemaVersion)
		return linkedMapOf(
			"schemaVersion" to DAILY_CAPTURE_SCHEMA_VERSION,
			"entries" to payload.entries.map { it.encode() },
		)
	}

	fun decode(map: Map<String, Any?>): DailyCapturePayload {
		val version = map.schemaVersion()
		if (version != DAILY_CAPTURE_SCHEMA_VERSION) unsupportedVersion(version)
		return DailyCapturePayload.fromEntries(
			map.requiredListOfMaps("entries").map { it.decodeEntry() },
		)
	}

	private fun DailyCaptureEntry.encode(): Map<String, Any?> = linkedMapOf(
		"entryId" to entryId.toString(),
		"type" to type.name,
		"mealType" to mealType?.name,
		"mealLabel" to mealLabel,
		"items" to items.map { it.encode() },
		"value" to value.jsonDecimal(),
		"unit" to unit?.name,
		"text" to text,
		"nutritionTotal" to nutritionTotal?.encode(),
	)

	private fun Map<String, Any?>.decodeEntry(): DailyCaptureEntry = DailyCaptureEntry(
		entryId = requiredUuid("entryId"),
		type = requiredEnum("type"),
		mealType = optionalEnum("mealType"),
		mealLabel = optionalString("mealLabel"),
		items = listOfMaps("items").map { it.decodeFoodItem() },
		value = decimal("value"),
		unit = optionalEnum("unit"),
		text = optionalString("text"),
		nutritionTotal = optionalMap("nutritionTotal")?.decodeNutritionValues(),
	)

	private fun DailyFoodCaptureItem.encode(): Map<String, Any?> = linkedMapOf(
		"itemId" to itemId.toString(),
		"sourceType" to sourceType.name,
		"userFoodId" to userFoodId?.toString(),
		"displayName" to displayName,
		"brand" to brand,
		"enteredQuantity" to enteredQuantity.encode(),
		"resolvedQuantity" to resolvedQuantity.encode(),
		"userFoodSnapshot" to userFoodSnapshot?.encode(),
		"calculatedNutrition" to calculatedNutrition.encode(),
	)

	private fun Map<String, Any?>.decodeFoodItem(): DailyFoodCaptureItem = DailyFoodCaptureItem(
		itemId = requiredUuid("itemId"),
		sourceType = requiredEnum("sourceType"),
		userFoodId = optionalUuid("userFoodId"),
		displayName = requiredString("displayName"),
		brand = optionalString("brand"),
		enteredQuantity = requiredMap("enteredQuantity").decodeEnteredQuantity(),
		resolvedQuantity = requiredMap("resolvedQuantity").decodeResolvedQuantity(),
		userFoodSnapshot = optionalMap("userFoodSnapshot")?.decodeUserFoodSnapshot(),
		calculatedNutrition = requiredMap("calculatedNutrition").decodeNutritionValues(),
	)

	private fun DailyEnteredQuantity.encode(): Map<String, Any?> = linkedMapOf(
		"amount" to amount.jsonDecimal(),
		"unit" to unit.name,
	)

	private fun Map<String, Any?>.decodeEnteredQuantity() = DailyEnteredQuantity(
		amount = requiredDecimal("amount"),
		unit = requiredEnum<DailyFoodQuantityUnit>("unit"),
	)

	private fun DailyResolvedQuantity.encode(): Map<String, Any?> = linkedMapOf(
		"amount" to amount.jsonDecimal(),
		"unit" to unit.name,
	)

	private fun Map<String, Any?>.decodeResolvedQuantity() = DailyResolvedQuantity(
		amount = requiredDecimal("amount"),
		unit = requiredEnum<DailyResolvedFoodUnit>("unit"),
	)

	private fun DailyUserFoodSnapshot.encode(): Map<String, Any?> = linkedMapOf(
		"nutritionBasis" to nutritionBasis.encode(),
		"nutrientsPerBasis" to nutrientsPerBasis.encode(),
		"defaultServing" to defaultServing?.encode(),
		"conversions" to conversions.encode(),
		"nutritionSource" to nutritionSource.encode(),
		"userFoodVersion" to userFoodVersion,
		"userFoodUpdatedAt" to userFoodUpdatedAt.toString(),
	)

	private fun Map<String, Any?>.decodeUserFoodSnapshot() = DailyUserFoodSnapshot(
		nutritionBasis = requiredMap("nutritionBasis").decodeBasis(),
		nutrientsPerBasis = requiredMap("nutrientsPerBasis").decodeNutritionValues(),
		defaultServing = optionalMap("defaultServing")?.decodeDefaultServing(),
		conversions = requiredMap("conversions").decodeConversions(),
		nutritionSource = requiredMap("nutritionSource").decodeNutritionSource(),
		userFoodVersion = requiredLong("userFoodVersion"),
		userFoodUpdatedAt = requiredInstant("userFoodUpdatedAt"),
	)

	private fun DailyFoodBasisSnapshot.encode(): Map<String, Any?> = linkedMapOf(
		"amount" to amount.jsonDecimal(),
		"unit" to unit.name,
	)

	private fun Map<String, Any?>.decodeBasis() = DailyFoodBasisSnapshot(
		amount = requiredDecimal("amount"),
		unit = requiredEnum<DailyFoodSnapshotUnit>("unit"),
	)

	private fun DailyFoodDefaultServingSnapshot.encode(): Map<String, Any?> = linkedMapOf(
		"amount" to amount.jsonDecimal(),
		"unit" to unit.name,
	)

	private fun Map<String, Any?>.decodeDefaultServing() = DailyFoodDefaultServingSnapshot(
		amount = requiredDecimal("amount"),
		unit = requiredEnum<DailyFoodSnapshotUnit>("unit"),
	)

	private fun DailyFoodConversionSnapshot.encode(): Map<String, Any?> = linkedMapOf(
		"gramsPerPiece" to gramsPerPiece.jsonDecimal(),
		"millilitersPerPiece" to millilitersPerPiece.jsonDecimal(),
		"gramsPerServing" to gramsPerServing.jsonDecimal(),
		"millilitersPerServing" to millilitersPerServing.jsonDecimal(),
	)

	private fun Map<String, Any?>.decodeConversions() = DailyFoodConversionSnapshot(
		gramsPerPiece = decimal("gramsPerPiece"),
		millilitersPerPiece = decimal("millilitersPerPiece"),
		gramsPerServing = decimal("gramsPerServing"),
		millilitersPerServing = decimal("millilitersPerServing"),
	)

	private fun DailyNutritionSourceSnapshot.encode(): Map<String, Any?> = linkedMapOf(
		"type" to type.name,
		"originalSourceType" to originalSourceType,
		"estimated" to estimated,
		"provider" to provider,
		"externalId" to externalId,
		"notes" to notes,
		"copiedAt" to copiedAt?.toString(),
	)

	private fun Map<String, Any?>.decodeNutritionSource() = DailyNutritionSourceSnapshot(
		type = requiredEnum<DailyFoodItemSourceType>("type"),
		originalSourceType = requiredString("originalSourceType"),
		estimated = requiredBoolean("estimated"),
		provider = optionalString("provider"),
		externalId = optionalString("externalId"),
		notes = optionalString("notes"),
		copiedAt = optionalString("copiedAt")?.let(LocalDate::parse),
	)

	private fun DailyNutritionValues.encode(): Map<String, Any?> = linkedMapOf(
		"caloriesKcal" to caloriesKcal.jsonDecimal(),
		"proteinGrams" to proteinGrams.jsonDecimal(),
		"carbohydratesGrams" to carbohydratesGrams.jsonDecimal(),
		"fatGrams" to fatGrams.jsonDecimal(),
		"fiberGrams" to fiberGrams.jsonDecimal(),
		"sugarsGrams" to sugarsGrams.jsonDecimal(),
		"saturatedFatGrams" to saturatedFatGrams.jsonDecimal(),
		"sodiumMilligrams" to sodiumMilligrams.jsonDecimal(),
		"saltGrams" to saltGrams.jsonDecimal(),
	)

	private fun Map<String, Any?>.decodeNutritionValues() = DailyNutritionValues(
		caloriesKcal = decimal("caloriesKcal"),
		proteinGrams = decimal("proteinGrams"),
		carbohydratesGrams = decimal("carbohydratesGrams"),
		fatGrams = decimal("fatGrams"),
		fiberGrams = decimal("fiberGrams"),
		sugarsGrams = decimal("sugarsGrams"),
		saturatedFatGrams = decimal("saturatedFatGrams"),
		sodiumMilligrams = decimal("sodiumMilligrams"),
		saltGrams = decimal("saltGrams"),
	)

	private fun Map<String, Any?>.schemaVersion(): Int {
		val raw = this["schemaVersion"]
			?: throw IllegalArgumentException("Missing daily capture payload schema version")
		return raw.toExactBigDecimal("schemaVersion").toExactInt("schemaVersion")
	}

	private fun Map<String, Any?>.requiredString(key: String): String = when (val value = this[key]) {
		is String -> value
		else -> error("Missing or invalid JSON field: $key")
	}

	private fun Map<String, Any?>.optionalString(key: String): String? = when (val value = this[key]) {
		null -> null
		is String -> value
		else -> error("Invalid JSON string field: $key")
	}

	private inline fun <reified T : Enum<T>> Map<String, Any?>.requiredEnum(key: String): T =
		enumValueOf(requiredString(key))

	private inline fun <reified T : Enum<T>> Map<String, Any?>.optionalEnum(key: String): T? =
		optionalString(key)?.let(::enumValueOf)

	private fun Map<String, Any?>.requiredUuid(key: String): UUID = UUID.fromString(requiredString(key))

	private fun Map<String, Any?>.optionalUuid(key: String): UUID? = optionalString(key)?.let(UUID::fromString)

	private fun Map<String, Any?>.requiredInstant(key: String): Instant = Instant.parse(requiredString(key))

	private fun Map<String, Any?>.requiredBoolean(key: String): Boolean =
		this[key] as? Boolean ?: error("Missing or invalid JSON boolean field: $key")

	private fun Map<String, Any?>.requiredDecimal(key: String): BigDecimal =
		decimal(key) ?: error("Missing numeric JSON field: $key")

	private fun Map<String, Any?>.decimal(key: String): BigDecimal? =
		this[key]?.toExactBigDecimal(key)

	private fun Any.toExactBigDecimal(key: String): BigDecimal = when (this) {
		is BigDecimal -> this
		is Number -> toString().toBigDecimal()
		is String -> toBigDecimal()
		else -> error("Invalid numeric JSON field: $key")
	}

	private fun BigDecimal.toExactInt(key: String): Int = try {
		intValueExact()
	} catch (exception: ArithmeticException) {
		throw IllegalArgumentException("Invalid integer JSON field: $key", exception)
	}

	private fun Map<String, Any?>.requiredLong(key: String): Long = try {
		requiredDecimal(key).longValueExact()
	} catch (exception: ArithmeticException) {
		throw IllegalArgumentException("Invalid long JSON field: $key", exception)
	}

	@Suppress("UNCHECKED_CAST")
	private fun Map<String, Any?>.requiredMap(key: String): Map<String, Any?> =
		this[key] as? Map<String, Any?> ?: error("Missing or invalid JSON object field: $key")

	@Suppress("UNCHECKED_CAST")
	private fun Map<String, Any?>.optionalMap(key: String): Map<String, Any?>? = when (val value = this[key]) {
		null -> null
		is Map<*, *> -> value as? Map<String, Any?> ?: error("Invalid JSON object field: $key")
		else -> error("Invalid JSON object field: $key")
	}

	@Suppress("UNCHECKED_CAST")
	private fun Map<String, Any?>.requiredListOfMaps(key: String): List<Map<String, Any?>> =
		(this[key] as? List<*>)?.mapIndexed { index, value ->
			value as? Map<String, Any?> ?: error("Invalid JSON object at $key[$index]")
		} ?: error("Missing or invalid JSON array field: $key")

	@Suppress("UNCHECKED_CAST")
	private fun Map<String, Any?>.listOfMaps(key: String): List<Map<String, Any?>> = when (val value = this[key]) {
		null -> emptyList()
		is List<*> -> value.mapIndexed { index, element ->
			element as? Map<String, Any?> ?: error("Invalid JSON object at $key[$index]")
		}
		else -> error("Invalid JSON array field: $key")
	}

	private fun unsupportedVersion(version: Int): Nothing =
		throw IllegalArgumentException("Unsupported daily capture payload schema version: $version")

	private fun BigDecimal?.jsonDecimal(): String? = this?.toPlainString()
}
