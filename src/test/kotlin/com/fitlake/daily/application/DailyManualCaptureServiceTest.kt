package com.fitlake.daily.application

import ch.qos.logback.classic.Level
import com.fitlake.daily.application.capture.DailyCaptureContentFactory
import com.fitlake.daily.application.capture.DailyCaptureContentInput
import com.fitlake.daily.application.capture.DailyCaptureEntryInput
import com.fitlake.daily.application.capture.DailyEnteredFoodQuantityInput
import com.fitlake.daily.application.capture.DailyFoodItemInput
import com.fitlake.daily.application.capture.DailyManualCaptureService
import com.fitlake.daily.application.port.DailyOwnedUserFood
import com.fitlake.daily.application.port.DailyUserFoodLookupPort
import com.fitlake.daily.domain.audit.DailyCaptureAuditAction
import com.fitlake.daily.domain.capture.DailyCapture
import com.fitlake.daily.domain.capture.DailyCaptureActor
import com.fitlake.daily.domain.capture.DailyCaptureEntry
import com.fitlake.daily.domain.capture.DailyCaptureEntryType
import com.fitlake.daily.domain.capture.DailyCapturePayload
import com.fitlake.daily.domain.capture.DailyCaptureStatus
import com.fitlake.daily.domain.capture.DailyCaptureType
import com.fitlake.daily.domain.capture.DailyEnteredQuantity
import com.fitlake.daily.domain.capture.DailyFoodBasisSnapshot
import com.fitlake.daily.domain.capture.DailyFoodCaptureItem
import com.fitlake.daily.domain.capture.DailyFoodConversionSnapshot
import com.fitlake.daily.domain.capture.DailyFoodDefaultServingSnapshot
import com.fitlake.daily.domain.capture.DailyFoodItemSourceType
import com.fitlake.daily.domain.capture.DailyFoodQuantityUnit
import com.fitlake.daily.domain.capture.DailyFoodSnapshotUnit
import com.fitlake.daily.domain.capture.DailyNutritionSourceSnapshot
import com.fitlake.daily.domain.capture.DailyNutritionValues
import com.fitlake.daily.domain.capture.DailyResolvedFoodUnit
import com.fitlake.daily.domain.capture.DailyResolvedQuantity
import com.fitlake.daily.domain.capture.DailyScalarUnit
import com.fitlake.support.ImmediateTransactionExecutor
import com.fitlake.support.InMemoryDailyCaptureAuditRepository
import com.fitlake.support.InMemoryDailyCaptureRepository
import com.fitlake.support.InMemoryDailyDayRepository
import com.fitlake.support.InMemoryDailyMetricsRepository
import com.fitlake.support.LogEventCapture
import com.fitlake.support.renderedLogContent
import com.fitlake.support.structuredFields
import com.fitlake.user.domain.UserId
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class DailyManualCaptureServiceTest {
	private val userId = UserId(UUID.randomUUID())
	private val otherUserId = UserId(UUID.randomUUID())
	private val date = LocalDate.parse("2026-07-31")
	private val clock = Clock.fixed(Instant.parse("2026-07-31T12:00:00Z"), ZoneOffset.UTC)
	private val days = InMemoryDailyDayRepository()
	private val captures = InMemoryDailyCaptureRepository()
	private val metrics = InMemoryDailyMetricsRepository()
	private val audits = InMemoryDailyCaptureAuditRepository()
	private val foods = FakeDailyUserFoodLookup()
	private val contentFactory = DailyCaptureContentFactory(foods)
	private val service = DailyManualCaptureService(
		dayRepository = days,
		captureRepository = captures,
		metricsRepository = metrics,
		auditRepository = audits,
		contentFactory = contentFactory,
		transactionExecutor = ImmediateTransactionExecutor,
		clock = clock,
	)

	@Test
	fun `manual mixed creation derives type generates IDs and stores authoritative snapshot`() {
		val foodId = foods.add(userId, food(defaultServingAmount = "170"))

		val created = service.create(
			userId,
			date,
			DailyCaptureContentInput(
				listOf(
					foodEntry(foodId, "1", DailyFoodQuantityUnit.DEFAULT_SERVING),
					DailyCaptureEntryInput(
						entryId = null,
						type = DailyCaptureEntryType.WEIGHT,
						value = bd("78000"),
						unit = DailyScalarUnit.GRAM,
					),
				),
			),
		)

		assertEquals(DailyCaptureType.MIXED, created.captureType)
		assertEquals(DailyCaptureStatus.OPEN, created.status)
		assertEquals(2, created.payload.schemaVersion)
		assertEquals(bd("78"), created.payload.fields.bodyWeightKg)
		val foodEntry = created.payload.entries.first { it.type == DailyCaptureEntryType.FOOD }
		val item = foodEntry.items.single()
		assertNotNull(foodEntry.entryId)
		assertNotNull(item.itemId)
		assertEquals(foodId, item.userFoodId)
		assertEquals("Greek yogurt", item.displayName)
		assertEquals(bd("170.000000"), item.resolvedQuantity.amount)
		assertEquals(DailyResolvedFoodUnit.GRAM, item.resolvedQuantity.unit)
		assertEquals(bd("105.400000"), item.calculatedNutrition.caloriesKcal)
		assertEquals(bd("105.4"), foodEntry.nutritionTotal?.caloriesKcal)
		assertEquals(4L, item.userFoodSnapshot?.userFoodVersion)
		assertEquals(1, captures.count())
		val audit = audits.all().single()
		assertEquals(DailyCaptureAuditAction.CREATE, audit.action)
		assertEquals(DailyCaptureActor.USER_UI, audit.actor)
		assertEquals(created.payload, audit.newPayload)
		assertEquals(DailyCaptureStatus.OPEN, audit.newStatus)
		assertEquals(created.version, audit.newVersion)
	}

	@Test
	fun `manual create and replace logs contain lifecycle metadata without food health or payload data`() {
		val privateFoodName = "PRIVATE_FOOD_NAME_9a51"
		val privateCalories = "731.246"
		val privateInitialWeight = "87654.321"
		val privateReplacementWeight = "91234.567"
		val foodId = foods.add(
			userId,
			food(name = privateFoodName, calories = privateCalories),
		)
		val initialInput = DailyCaptureContentInput(
			listOf(
				foodEntry(foodId, "137.249"),
				DailyCaptureEntryInput(
					entryId = null,
					type = DailyCaptureEntryType.WEIGHT,
					value = bd(privateInitialWeight),
					unit = DailyScalarUnit.GRAM,
				),
			),
		)

		lateinit var created: DailyCapture
		val createEvents = LogEventCapture(DailyManualCaptureService::class.java).use { logs ->
			created = service.create(userId, date, initialInput)
			logs.events
		}
		val createFields = createEvents
			.single { it.structuredFields()["event"] == "daily_capture_created" }
			.structuredFields()
		assertEquals("success", createFields["outcome"])
		assertEquals(userId.value, createFields["userRef"])
		assertEquals(created.captureId.value, createFields["captureId"])
		assertEquals(DailyCaptureType.MIXED, createFields["captureType"])
		assertEquals(DailyCaptureStatus.OPEN, createFields["captureStatus"])
		assertEquals(DailyCaptureActor.USER_UI, createFields["sourceType"])
		assertEquals(2, createFields["entryCount"])
		assertEquals(1, createFields["foodItemCount"])
		assertEquals(created.version, createFields["newVersion"])
		assertTrue(createFields["durationMs"] is Long)
		assertFalse(createFields.containsKey("payload"))
		assertFalse(createFields.containsKey("foodName"))
		assertFalse(createFields.containsKey("bodyWeightKg"))
		assertFalse(createFields.containsKey("nutrition"))
		val createLogContent = createEvents.renderedLogContent()
		listOf(
			privateFoodName,
			privateCalories,
			privateInitialWeight,
			"87.654321",
			"137.249",
			"Example Brand",
		).forEach {
			assertFalse(createLogContent.contains(it), "Private Daily content leaked in create log: $it")
		}

		val oldFoodEntry = created.payload.entries.single { it.type == DailyCaptureEntryType.FOOD }
		val oldFoodItem = oldFoodEntry.items.single()
		val oldWeightEntry = created.payload.entries.single { it.type == DailyCaptureEntryType.WEIGHT }
		lateinit var replaced: DailyCapture
		val replaceEvents = LogEventCapture(DailyManualCaptureService::class.java).use { logs ->
			replaced = service.replace(
				userId = userId,
				captureId = created.captureId,
				expectedVersion = created.version,
				input = DailyCaptureContentInput(
					listOf(
						foodEntry(
							foodId = foodId,
							amount = "143.257",
							entryId = oldFoodEntry.entryId,
							itemId = oldFoodItem.itemId,
						),
						DailyCaptureEntryInput(
							entryId = oldWeightEntry.entryId,
							type = DailyCaptureEntryType.WEIGHT,
							value = bd(privateReplacementWeight),
							unit = DailyScalarUnit.GRAM,
						),
					),
				),
				requestId = "safe-request-id",
			)
			logs.events
		}
		val replaceFields = replaceEvents
			.single { it.structuredFields()["event"] == "daily_capture_content_replaced" }
			.structuredFields()
		assertEquals("success", replaceFields["outcome"])
		assertEquals(userId.value, replaceFields["userRef"])
		assertEquals(created.captureId.value, replaceFields["captureId"])
		assertEquals(DailyCaptureStatus.OPEN, replaceFields["oldStatus"])
		assertEquals(DailyCaptureStatus.OPEN, replaceFields["newStatus"])
		assertEquals(created.version, replaceFields["oldVersion"])
		assertEquals(replaced.version, replaceFields["newVersion"])
		assertEquals(2, replaceFields["entryCount"])
		assertEquals(1, replaceFields["foodItemCount"])
		assertTrue(replaceFields["durationMs"] is Long)
		assertFalse(replaceFields.containsKey("payload"))
		assertFalse(replaceFields.containsKey("foodName"))
		assertFalse(replaceFields.containsKey("bodyWeightKg"))
		assertFalse(replaceFields.containsKey("nutrition"))
		val replaceLogContent = replaceEvents.renderedLogContent()
		listOf(
			privateFoodName,
			privateCalories,
			privateInitialWeight,
			privateReplacementWeight,
			"87.654321",
			"91.234567",
			"137.249",
			"143.257",
			"Example Brand",
		).forEach {
			assertFalse(replaceLogContent.contains(it), "Private Daily content leaked in replace log: $it")
		}
	}

	@Test
	fun `stale replacement logs a bounded version conflict without capture content`() {
		val privateFoodName = "PRIVATE_CONFLICT_FOOD_19"
		val privateAmount = "987.654"
		val foodId = foods.add(userId, food(name = privateFoodName))
		val created = service.create(
			userId,
			date,
			DailyCaptureContentInput(listOf(foodEntry(foodId, "100"))),
		)

		val events = LogEventCapture(DailyManualCaptureService::class.java).use { logs ->
			assertFailsWith<DailyConflictException> {
				service.replace(
					userId = userId,
					captureId = created.captureId,
					expectedVersion = created.version + 1,
					input = DailyCaptureContentInput(listOf(foodEntry(foodId, privateAmount))),
					requestId = "safe-conflict-request",
				)
			}
			logs.events
		}

		val event = events.single { it.structuredFields()["event"] == "daily_capture_version_conflict" }
		val fields = event.structuredFields()
		assertEquals(Level.WARN, event.level)
		assertEquals("rejected", fields["outcome"])
		assertEquals("DAILY_CAPTURE_VERSION_CONFLICT", fields["errorCode"])
		assertEquals(userId.value, fields["userRef"])
		assertEquals(created.captureId.value, fields["captureId"])
		assertEquals(created.version + 1, fields["expectedVersion"])
		assertEquals(created.version, fields["actualVersion"])
		assertTrue(fields["durationMs"] is Long)
		assertFalse(fields.containsKey("payload"))
		val rendered = events.renderedLogContent()
		assertFalse(rendered.contains(privateFoodName))
		assertFalse(rendered.contains(privateAmount))
	}

	@Test
	fun `one food entry may contain multiple exact user foods and totals are strict sums`() {
		val yogurt = foods.add(userId, food(calories = "62"))
		val oats = foods.add(userId, food(name = "Oats", calories = "380"))
		val entry = foodEntry(yogurt, "100").copy(
			items = listOf(
				foodItem(yogurt, "100"),
				foodItem(oats, "50"),
			),
		)

		val created = service.create(userId, date, DailyCaptureContentInput(listOf(entry)))

		assertEquals(DailyCaptureType.FOOD, created.captureType)
		assertEquals(2, created.payload.entries.single().items.size)
		assertEquals(bd("252"), created.payload.entries.single().nutritionTotal?.caloriesKcal)
	}

	@Test
	fun `foreign or deleted food is ownership-safe not found`() {
		val foreignFoodId = foods.add(otherUserId, food())

		assertFailsWith<DailyNotFoundException> {
			service.create(userId, date, DailyCaptureContentInput(listOf(foodEntry(foreignFoodId, "100"))))
		}
		assertEquals(0, captures.count())
		assertEquals(0, audits.count())
	}

	@Test
	fun `unchanged item preserves snapshot and quantity-only edit recalculates from it after catalog deletion`() {
		val foodId = foods.add(userId, food(calories = "62"))
		val created = service.create(userId, date, DailyCaptureContentInput(listOf(foodEntry(foodId, "100"))))
		val originalEntry = created.payload.entries.single()
		val originalItem = originalEntry.items.single()

		foods.add(userId, food(id = foodId, calories = "999", version = 5))
		val unchanged = service.replace(
			userId,
			created.captureId,
			created.version,
			DailyCaptureContentInput(
				listOf(foodEntry(foodId, "100", entryId = originalEntry.entryId, itemId = originalItem.itemId)),
			),
			"request-1",
		)
		val unchangedItem = unchanged.payload.entries.single().items.single()
		assertSame(originalItem, unchangedItem)
		assertEquals(bd("62.000000"), unchangedItem.calculatedNutrition.caloriesKcal)

		foods.remove(userId, foodId)
		val changedQuantity = service.replace(
			userId,
			created.captureId,
			unchanged.version,
			DailyCaptureContentInput(
				listOf(foodEntry(foodId, "200", entryId = originalEntry.entryId, itemId = originalItem.itemId)),
			),
			"request-2",
		)
		val changedItem = changedQuantity.payload.entries.single().items.single()
		assertEquals(originalItem.userFoodSnapshot, changedItem.userFoodSnapshot)
		assertEquals(bd("124.000000"), changedItem.calculatedNutrition.caloriesKcal)
		assertEquals(3, audits.count())
		assertEquals(created.version, audits.all().single { it.requestId == "request-1" }.oldVersion)
		assertEquals(changedQuantity.version, audits.all().single { it.requestId == "request-2" }.newVersion)
	}

	@Test
	fun `changed food reference loads a fresh snapshot while IDs remain stable`() {
		val oldFoodId = foods.add(userId, food(calories = "62"))
		val newFoodId = foods.add(userId, food(name = "Oats", calories = "380", version = 9))
		val created = service.create(userId, date, DailyCaptureContentInput(listOf(foodEntry(oldFoodId, "100"))))
		val oldEntry = created.payload.entries.single()
		val oldItem = oldEntry.items.single()

		val replaced = service.replace(
			userId,
			created.captureId,
			created.version,
			DailyCaptureContentInput(
				listOf(foodEntry(newFoodId, "100", entryId = oldEntry.entryId, itemId = oldItem.itemId)),
			),
			null,
		)
		val newItem = replaced.payload.entries.single().items.single()

		assertEquals(oldEntry.entryId, replaced.payload.entries.single().entryId)
		assertEquals(oldItem.itemId, newItem.itemId)
		assertEquals(newFoodId, newItem.userFoodId)
		assertNotEquals(oldItem.userFoodSnapshot, newItem.userFoodSnapshot)
		assertEquals(9L, newItem.userFoodSnapshot?.userFoodVersion)
		assertEquals(bd("380.000000"), newItem.calculatedNutrition.caloriesKcal)
	}

	@Test
	fun `full replacement removes omitted content derives new type and rejects stale or foreign IDs`() {
		val foodId = foods.add(userId, food())
		val created = service.create(
			userId,
			date,
			DailyCaptureContentInput(
				listOf(
					foodEntry(foodId, "100"),
					DailyCaptureEntryInput(null, DailyCaptureEntryType.HYDRATION, value = bd("1"), unit = DailyScalarUnit.LITER),
				),
			),
		)
		val hydration = created.payload.entries.first { it.type == DailyCaptureEntryType.HYDRATION }

		val replaced = service.replace(
			userId,
			created.captureId,
			created.version,
			DailyCaptureContentInput(listOf(hydration.copy(value = bd("2"), items = emptyList()).toInput())),
			null,
		)

		assertEquals(DailyCaptureType.DAILY_FIELDS, replaced.captureType)
		assertEquals(listOf(DailyCaptureEntryType.HYDRATION), replaced.payload.entries.map { it.type })
		assertEquals(bd("2"), replaced.payload.fields.hydrationLiters)
		assertFailsWith<DailyConflictException> {
			service.replace(
				userId,
				created.captureId,
				created.version,
				DailyCaptureContentInput(listOf(hydration.toInput())),
				null,
			)
		}
		assertEquals(replaced.payload, captures.findById(replaced.captureId)?.payload)

		assertFailsWith<DailyValidationException> {
			service.replace(
				userId,
				replaced.captureId,
				replaced.version,
				DailyCaptureContentInput(
					listOf(DailyCaptureEntryInput(UUID.randomUUID(), DailyCaptureEntryType.WEIGHT, value = bd("78"), unit = DailyScalarUnit.KILOGRAM)),
				),
				null,
			)
		}
	}

	@Test
	fun `accepted remains accepted while rejected captures are not editable`() {
		val foodId = foods.add(userId, food())
		val created = service.create(userId, date, DailyCaptureContentInput(listOf(foodEntry(foodId, "100"))))
		val accepted = captures.save(created.accept(clock.instant()))
		val entry = accepted.payload.entries.single()
		val item = entry.items.single()

		val edited = service.replace(
			userId,
			accepted.captureId,
			accepted.version,
			DailyCaptureContentInput(
				listOf(foodEntry(foodId, "120", entryId = entry.entryId, itemId = item.itemId)),
			),
			null,
		)
		assertEquals(DailyCaptureStatus.ACCEPTED, edited.status)

		val second = service.create(userId, date, DailyCaptureContentInput(listOf(foodEntry(foodId, "50"))))
		val rejected = captures.save(second.reject(clock.instant()))
		assertFailsWith<DailyConflictException> {
			service.replace(
				userId,
				rejected.captureId,
				rejected.version,
				DailyCaptureContentInput(listOf(foodEntry(foodId, "60"))),
				null,
			)
		}
	}

	@Test
	fun `full typed replacement preserves an unchanged AI estimate and may remove it by omission`() {
		val existing = aiEstimatePayload(includeHydration = true)
		val foodEntry = existing.entries.first { it.type == DailyCaptureEntryType.FOOD }
		val estimatedItem = foodEntry.items.single()

		val preserved = contentFactory.replace(
			userId,
			existing,
			DailyCaptureContentInput(
				listOf(
					DailyCaptureEntryInput(
						entryId = foodEntry.entryId,
						type = DailyCaptureEntryType.FOOD,
						items = listOf(
							DailyFoodItemInput(
								itemId = estimatedItem.itemId,
								sourceType = DailyFoodItemSourceType.AI_ESTIMATE,
								userFoodId = null,
								quantity = DailyEnteredFoodQuantityInput(
									estimatedItem.enteredQuantity.amount,
									estimatedItem.enteredQuantity.unit,
								),
							),
						),
					),
					existing.entries.first { it.type == DailyCaptureEntryType.HYDRATION }.toInput(),
				),
			),
		)

		assertSame(estimatedItem, preserved.entries.first { it.type == DailyCaptureEntryType.FOOD }.items.single())
		assertEquals(DailyFoodItemSourceType.AI_ESTIMATE, preserved.entries.first().items.single().sourceType)

		val hydration = existing.entries.first { it.type == DailyCaptureEntryType.HYDRATION }
		val withoutEstimate = contentFactory.replace(
			userId,
			existing,
			DailyCaptureContentInput(listOf(hydration.toInput())),
		)

		assertEquals(listOf(DailyCaptureEntryType.HYDRATION), withoutEstimate.entries.map { it.type })
		assertEquals(DailyCaptureType.DAILY_FIELDS, withoutEstimate.type)
	}

	@Test
	fun `typed content lets an AI estimate become an exact user food but rejects client-authored estimates`() {
		val existingEstimate = aiEstimatePayload()
		val estimateEntry = existingEstimate.entries.single()
		val estimateItem = estimateEntry.items.single()
		val estimateInput = DailyFoodItemInput(
			itemId = estimateItem.itemId,
			sourceType = DailyFoodItemSourceType.AI_ESTIMATE,
			userFoodId = null,
			quantity = DailyEnteredFoodQuantityInput(
				estimateItem.enteredQuantity.amount,
				estimateItem.enteredQuantity.unit,
			),
		)

		assertFailsWith<DailyValidationException> {
			contentFactory.create(
				userId,
				DailyCaptureContentInput(
					listOf(DailyCaptureEntryInput(null, DailyCaptureEntryType.FOOD, items = listOf(estimateInput.copy(itemId = null)))),
				),
			)
		}

		val foodId = foods.add(userId, food())
		val linked = contentFactory.create(userId, DailyCaptureContentInput(listOf(foodEntry(foodId, "100"))))
		val linkedEntry = linked.entries.single()
		val linkedItem = linkedEntry.items.single()
		assertFailsWith<DailyValidationException> {
			contentFactory.replace(
				userId,
				linked,
				DailyCaptureContentInput(
					listOf(
						DailyCaptureEntryInput(
							linkedEntry.entryId,
							DailyCaptureEntryType.FOOD,
							items = listOf(
								estimateInput.copy(
									itemId = linkedItem.itemId,
									quantity = DailyEnteredFoodQuantityInput(
										linkedItem.enteredQuantity.amount,
										linkedItem.enteredQuantity.unit,
									),
								),
							),
						),
					),
				),
			)
		}

		val converted = contentFactory.replace(
			userId,
			existingEstimate,
			DailyCaptureContentInput(
				listOf(
					DailyCaptureEntryInput(
						estimateEntry.entryId,
						DailyCaptureEntryType.FOOD,
						items = listOf(
							DailyFoodItemInput(
								itemId = estimateItem.itemId,
								sourceType = DailyFoodItemSourceType.USER_FOOD,
								userFoodId = foodId,
								quantity = DailyEnteredFoodQuantityInput(
									estimateItem.enteredQuantity.amount,
									estimateItem.enteredQuantity.unit,
								),
							),
						),
					),
				),
			),
		)
		val convertedItem = converted.entries.single().items.single()
		assertEquals(estimateItem.itemId, convertedItem.itemId)
		assertEquals(DailyFoodItemSourceType.USER_FOOD, convertedItem.sourceType)
		assertEquals(foodId, convertedItem.userFoodId)
		assertNotNull(convertedItem.userFoodSnapshot)

		assertFailsWith<DailyValidationException> {
			contentFactory.replace(
				userId,
				existingEstimate,
				DailyCaptureContentInput(
					listOf(
						DailyCaptureEntryInput(
							estimateEntry.entryId,
							DailyCaptureEntryType.FOOD,
							items = listOf(
								estimateInput.copy(
									quantity = DailyEnteredFoodQuantityInput(
										estimateItem.enteredQuantity.amount.add(BigDecimal.ONE),
										estimateItem.enteredQuantity.unit,
									),
								),
							),
						),
					),
				),
			)
		}
	}

	private fun foodEntry(
		foodId: UUID,
		amount: String,
		unit: DailyFoodQuantityUnit = DailyFoodQuantityUnit.GRAM,
		entryId: UUID? = null,
		itemId: UUID? = null,
	) = DailyCaptureEntryInput(
		entryId = entryId,
		type = DailyCaptureEntryType.FOOD,
		items = listOf(foodItem(foodId, amount, unit, itemId)),
	)

	private fun foodItem(
		foodId: UUID,
		amount: String,
		unit: DailyFoodQuantityUnit = DailyFoodQuantityUnit.GRAM,
		itemId: UUID? = null,
	) = DailyFoodItemInput(
		itemId = itemId,
		sourceType = DailyFoodItemSourceType.USER_FOOD,
		userFoodId = foodId,
		quantity = DailyEnteredFoodQuantityInput(bd(amount), unit),
	)

	private fun com.fitlake.daily.domain.capture.DailyCaptureEntry.toInput() = DailyCaptureEntryInput(
		entryId = entryId,
		type = type,
		mealType = mealType,
		mealLabel = mealLabel,
		value = value,
		unit = unit,
		text = text,
	)

	private fun food(
		id: UUID = UUID.randomUUID(),
		name: String = "Greek yogurt",
		calories: String = "62",
		defaultServingAmount: String? = null,
		version: Long = 4,
	) = DailyOwnedUserFood(
		userFoodId = id,
		displayName = name,
		brand = "Example Brand",
		nutritionBasis = DailyFoodBasisSnapshot(bd("100"), DailyFoodSnapshotUnit.GRAM),
		nutrientsPerBasis = DailyNutritionValues(
			caloriesKcal = bd(calories),
			proteinGrams = bd("9.5"),
			carbohydratesGrams = bd("4.1"),
			fatGrams = bd("0.2"),
		),
		defaultServing = defaultServingAmount?.let {
			DailyFoodDefaultServingSnapshot(bd(it), DailyFoodSnapshotUnit.GRAM)
		},
		conversions = DailyFoodConversionSnapshot(),
		nutritionSource = DailyNutritionSourceSnapshot(
			type = DailyFoodItemSourceType.USER_FOOD,
			originalSourceType = "PRODUCT_LABEL",
			estimated = false,
		),
		version = version,
		updatedAt = Instant.parse("2026-07-30T10:00:00Z"),
	)

	private fun aiEstimatePayload(includeHydration: Boolean = false): DailyCapturePayload {
		val nutrition = DailyNutritionValues(
			caloriesKcal = bd("165"),
			proteinGrams = bd("31"),
			carbohydratesGrams = bd("0"),
			fatGrams = bd("3.6"),
		)
		val item = DailyFoodCaptureItem(
			itemId = UUID.randomUUID(),
			sourceType = DailyFoodItemSourceType.AI_ESTIMATE,
			userFoodId = null,
			displayName = "pollo",
			brand = null,
			enteredQuantity = DailyEnteredQuantity(bd("100"), DailyFoodQuantityUnit.GRAM),
			resolvedQuantity = DailyResolvedQuantity(bd("100"), DailyResolvedFoodUnit.GRAM),
			userFoodSnapshot = null,
			calculatedNutrition = nutrition,
		)
		val entries = mutableListOf(
			DailyCaptureEntry(
				entryId = UUID.randomUUID(),
				type = DailyCaptureEntryType.FOOD,
				items = listOf(item),
				nutritionTotal = nutrition,
			),
		)
		if (includeHydration) {
			entries += DailyCaptureEntry(
				entryId = UUID.randomUUID(),
				type = DailyCaptureEntryType.HYDRATION,
				value = bd("2"),
				unit = DailyScalarUnit.LITER,
			)
		}
		return DailyCapturePayload.fromEntries(entries)
	}

	private fun bd(value: String) = BigDecimal(value)
}

private class FakeDailyUserFoodLookup : DailyUserFoodLookupPort {
	private val foods = mutableMapOf<Pair<UserId, UUID>, DailyOwnedUserFood>()

	fun add(userId: UserId, food: DailyOwnedUserFood): UUID {
		foods[userId to food.userFoodId] = food
		return food.userFoodId
	}

	fun remove(userId: UserId, foodId: UUID) {
		foods.remove(userId to foodId)
	}

	override fun findActiveOwnedFood(userId: UserId, userFoodId: UUID): DailyOwnedUserFood? =
		foods[userId to userFoodId]
}
