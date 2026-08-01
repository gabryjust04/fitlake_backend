package com.fitlake.daily.application.capture

import com.fitlake.daily.domain.capture.DAILY_CAPTURE_SCHEMA_VERSION
import com.fitlake.daily.domain.capture.DailyCaptureEntry
import com.fitlake.daily.domain.capture.DailyCaptureEntryType
import com.fitlake.daily.domain.capture.DailyCapturePayload
import com.fitlake.daily.domain.capture.DailyCaptureType
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
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull

class DailyCapturePayloadCodecTest {
	@Test
	fun `payload without schema version is rejected`() {
		val legacyMap = linkedMapOf<String, Any?>(
			"type" to "FOOD",
			"meals" to listOf(
				linkedMapOf(
					"mealTempId" to "breakfast",
					"mealName" to "colazione",
					"items" to listOf(
						linkedMapOf(
							"itemTempId" to "oats",
							"foodName" to "avena",
							"quantity" to 40,
							"unit" to "g",
							"calories" to 150,
							"proteinG" to "5.25",
							"carbsG" to BigDecimal("27.125"),
							"fatG" to 3,
						),
					),
				),
			),
			"fields" to emptyMap<String, Any?>(),
			"note" to null,
		)

		assertFailsWith<IllegalArgumentException> { DailyCapturePayloadCodec.decode(legacyMap) }
	}

	@Test
	fun `v2 typed entries round trip every snapshot field without duplicating the derived projection`() {
		val payload = completeV2Payload()

		val encoded = DailyCapturePayloadCodec.encode(payload)
		val decoded = DailyCapturePayloadCodec.decode(encoded)

		assertEquals(DAILY_CAPTURE_SCHEMA_VERSION, encoded["schemaVersion"])
		assertFalse(encoded.containsKey("type"))
		assertFalse(encoded.containsKey("meals"))
		assertFalse(encoded.containsKey("fields"))
		assertEquals(payload, decoded)
		assertEquals(DailyCaptureType.MIXED, decoded.type)
		assertEquals(10, decoded.entries.size)
		val linkedItem = decoded.entries.first().items.first()
		assertEquals(BigDecimal("170"), linkedItem.resolvedQuantity.amount)
		assertEquals(BigDecimal("9.876543"), linkedItem.userFoodSnapshot?.nutrientsPerBasis?.fiberGrams)
		assertEquals(Instant.parse("2026-07-31T09:15:30.123456Z"), linkedItem.userFoodSnapshot?.userFoodUpdatedAt)
		assertEquals("catalog-import", linkedItem.userFoodSnapshot?.nutritionSource?.provider)
		assertEquals("external-42", linkedItem.userFoodSnapshot?.nutritionSource?.externalId)
		assertEquals("Copied source metadata", linkedItem.userFoodSnapshot?.nutritionSource?.notes)
		assertEquals(LocalDate.parse("2026-07-30"), linkedItem.userFoodSnapshot?.nutritionSource?.copiedAt)
		assertNull(decoded.note)
	}

	@Test
	fun `v2 note entry and nullable snapshot fields round trip`() {
		val payload = DailyCapturePayload.fromEntries(
			listOf(
				DailyCaptureEntry(
					entryId = uuid("00000000-0000-0000-0000-000000000099"),
					type = DailyCaptureEntryType.NOTE,
					text = "Nota storica",
				),
			),
		)

		assertEquals(payload, DailyCapturePayloadCodec.decode(DailyCapturePayloadCodec.encode(payload)))
	}

	@Test
	fun `v2 AI estimate with complete core nutrition round trips`() {
		val nutrition = DailyNutritionValues(
			caloriesKcal = BigDecimal("165.125"),
			proteinGrams = BigDecimal("31.25"),
			carbohydratesGrams = BigDecimal("0.5"),
			fatGrams = BigDecimal("3.625"),
		)
		val item = DailyFoodCaptureItem(
			itemId = uuid("00000000-0000-0000-0000-000000000088"),
			sourceType = DailyFoodItemSourceType.AI_ESTIMATE,
			userFoodId = null,
			displayName = "pollo",
			brand = null,
			enteredQuantity = DailyEnteredQuantity(BigDecimal("100"), DailyFoodQuantityUnit.GRAM),
			resolvedQuantity = DailyResolvedQuantity(BigDecimal("100"), DailyResolvedFoodUnit.GRAM),
			userFoodSnapshot = null,
			calculatedNutrition = nutrition,
		)
		val payload = DailyCapturePayload.fromEntries(
			listOf(
				DailyCaptureEntry(
					entryId = uuid("00000000-0000-0000-0000-000000000089"),
					type = DailyCaptureEntryType.FOOD,
					items = listOf(item),
					nutritionTotal = nutrition,
				),
			),
		)

		val decoded = DailyCapturePayloadCodec.decode(DailyCapturePayloadCodec.encode(payload))
		val decodedItem = decoded.entries.single().items.single()

		assertEquals(payload, decoded)
		assertEquals(DailyFoodItemSourceType.AI_ESTIMATE, decodedItem.sourceType)
		assertNull(decodedItem.userFoodId)
		assertNull(decodedItem.userFoodSnapshot)
		assertEquals(BigDecimal("165.125"), decodedItem.calculatedNutrition.caloriesKcal)
		assertEquals(BigDecimal("31.25"), decodedItem.calculatedNutrition.proteinGrams)
		assertEquals(BigDecimal("0.5"), decodedItem.calculatedNutrition.carbohydratesGrams)
		assertEquals(BigDecimal("3.625"), decodedItem.calculatedNutrition.fatGrams)
	}

	@Test
	fun `explicit v1 future and fractional schema versions are rejected`() {
		val explicitV1 = mapOf<String, Any?>(
			"schemaVersion" to 1,
			"type" to "NOTE",
			"meals" to emptyList<Map<String, Any?>>(),
			"fields" to emptyMap<String, Any?>(),
			"note" to "legacy",
		)
		assertFailsWith<IllegalArgumentException> { DailyCapturePayloadCodec.decode(explicitV1) }

		assertFailsWith<IllegalArgumentException> {
			DailyCapturePayloadCodec.decode(mapOf("schemaVersion" to 3))
		}
		assertFailsWith<IllegalArgumentException> {
			DailyCapturePayloadCodec.decode(mapOf("schemaVersion" to "2.5"))
		}
	}

	private fun completeV2Payload(): DailyCapturePayload {
		val linkedNutrition = nutrition("105.4", "16.15", "6.97", "0.34", "9.876543")
		val manualNutrition = nutrition("42.1", "1.25", "6.5", "1.4", "0.5")
		val linkedItem = DailyFoodCaptureItem(
			itemId = uuid("00000000-0000-0000-0000-000000000011"),
			sourceType = DailyFoodItemSourceType.USER_FOOD,
			userFoodId = uuid("00000000-0000-0000-0000-000000000012"),
			displayName = "My usual Greek yogurt",
			brand = "Example Brand",
			enteredQuantity = DailyEnteredQuantity(BigDecimal.ONE, DailyFoodQuantityUnit.DEFAULT_SERVING),
			resolvedQuantity = DailyResolvedQuantity(BigDecimal("170"), DailyResolvedFoodUnit.GRAM),
			userFoodSnapshot = DailyUserFoodSnapshot(
				nutritionBasis = DailyFoodBasisSnapshot(BigDecimal("100"), DailyFoodSnapshotUnit.GRAM),
				nutrientsPerBasis = nutrition("62", "9.5", "4.1", "0.2", "9.876543"),
				defaultServing = DailyFoodDefaultServingSnapshot(BigDecimal("170"), DailyFoodSnapshotUnit.GRAM),
				conversions = DailyFoodConversionSnapshot(
					gramsPerPiece = BigDecimal("12.25"),
					gramsPerServing = BigDecimal("170"),
				),
					nutritionSource = DailyNutritionSourceSnapshot(
						type = DailyFoodItemSourceType.USER_FOOD,
						originalSourceType = "PRODUCT_LABEL",
						estimated = false,
						provider = "catalog-import",
						externalId = "external-42",
						notes = "Copied source metadata",
						copiedAt = LocalDate.parse("2026-07-30"),
					),
				userFoodVersion = 7,
				userFoodUpdatedAt = Instant.parse("2026-07-31T09:15:30.123456Z"),
			),
			calculatedNutrition = linkedNutrition,
		)
		val manualItem = DailyFoodCaptureItem(
			itemId = uuid("00000000-0000-0000-0000-000000000013"),
			sourceType = DailyFoodItemSourceType.MANUAL_NUTRITION,
			userFoodId = null,
			displayName = "Legacy biscuit",
			brand = null,
			enteredQuantity = DailyEnteredQuantity(BigDecimal("2"), DailyFoodQuantityUnit.PIECE),
			resolvedQuantity = DailyResolvedQuantity(BigDecimal("2"), DailyResolvedFoodUnit.PIECE),
			userFoodSnapshot = null,
			calculatedNutrition = manualNutrition,
		)
		val foodEntry = DailyCaptureEntry(
			entryId = uuid("00000000-0000-0000-0000-000000000001"),
			type = DailyCaptureEntryType.FOOD,
			mealType = DailyMealType.BREAKFAST,
			mealLabel = "post workout",
			items = listOf(linkedItem, manualItem),
			nutritionTotal = DailyNutritionValues.strictTotal(listOf(linkedNutrition, manualNutrition)),
		)
		return DailyCapturePayload.fromEntries(
			listOf(
				foodEntry,
				scalar("00000000-0000-0000-0000-000000000002", DailyCaptureEntryType.WEIGHT, "78.4", DailyScalarUnit.KILOGRAM),
				scalar("00000000-0000-0000-0000-000000000003", DailyCaptureEntryType.SLEEP, "7.5", DailyScalarUnit.HOUR),
				scalar("00000000-0000-0000-0000-000000000004", DailyCaptureEntryType.STEPS, "12345", DailyScalarUnit.COUNT),
				scalar("00000000-0000-0000-0000-000000000005", DailyCaptureEntryType.HYDRATION, "2.25", DailyScalarUnit.LITER),
				scalar("00000000-0000-0000-0000-000000000006", DailyCaptureEntryType.CAFFEINE, "120", DailyScalarUnit.MILLIGRAM),
				scalar("00000000-0000-0000-0000-000000000007", DailyCaptureEntryType.MOOD, "8", DailyScalarUnit.LEVEL),
				scalar("00000000-0000-0000-0000-000000000008", DailyCaptureEntryType.FOCUS, "7", DailyScalarUnit.LEVEL),
				scalar("00000000-0000-0000-0000-000000000009", DailyCaptureEntryType.STRESS, "3", DailyScalarUnit.LEVEL),
				DailyCaptureEntry(
					entryId = uuid("00000000-0000-0000-0000-000000000010"),
					type = DailyCaptureEntryType.DAILY_NOTES,
					text = "Giornata produttiva",
				),
			),
		)
	}

	private fun scalar(id: String, type: DailyCaptureEntryType, value: String, unit: DailyScalarUnit) =
		DailyCaptureEntry(entryId = uuid(id), type = type, value = BigDecimal(value), unit = unit)

	private fun nutrition(
		calories: String,
		protein: String,
		carbohydrates: String,
		fat: String,
		fiber: String,
	) = DailyNutritionValues(
		caloriesKcal = BigDecimal(calories),
		proteinGrams = BigDecimal(protein),
		carbohydratesGrams = BigDecimal(carbohydrates),
		fatGrams = BigDecimal(fat),
		fiberGrams = BigDecimal(fiber),
		sugarsGrams = BigDecimal("1.125"),
		saturatedFatGrams = BigDecimal("0.75"),
		sodiumMilligrams = BigDecimal("40.5"),
		saltGrams = BigDecimal("0.125"),
	)

	private fun uuid(value: String): UUID = UUID.fromString(value)
}
