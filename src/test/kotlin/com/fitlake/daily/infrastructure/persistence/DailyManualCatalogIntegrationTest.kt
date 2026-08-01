package com.fitlake.daily.infrastructure.persistence

import com.fitlake.daily.application.DailyNotFoundException
import com.fitlake.daily.application.capture.DailyCaptureContentFactory
import com.fitlake.daily.application.capture.DailyCaptureContentInput
import com.fitlake.daily.application.capture.DailyCaptureEntryInput
import com.fitlake.daily.application.capture.DailyEnteredFoodQuantityInput
import com.fitlake.daily.application.capture.DailyFoodItemInput
import com.fitlake.daily.application.capture.DailyManualCaptureService
import com.fitlake.daily.domain.capture.DAILY_CAPTURE_SCHEMA_VERSION
import com.fitlake.daily.domain.capture.DailyCaptureActor
import com.fitlake.daily.domain.capture.DailyCaptureEntryType
import com.fitlake.daily.domain.capture.DailyCaptureStatus
import com.fitlake.daily.domain.capture.DailyCaptureType
import com.fitlake.daily.domain.capture.DailyFoodItemSourceType
import com.fitlake.daily.domain.capture.DailyFoodQuantityUnit
import com.fitlake.daily.domain.capture.DailyFoodSnapshotUnit
import com.fitlake.daily.domain.capture.DailyMealType
import com.fitlake.daily.domain.capture.DailyResolvedFoodUnit
import com.fitlake.daily.infrastructure.food.CatalogDailyUserFoodLookupAdapter
import com.fitlake.daily.infrastructure.persistence.mapper.DailyCaptureAuditPersistenceMapper
import com.fitlake.daily.infrastructure.persistence.mapper.DailyPersistenceMapper
import com.fitlake.daily.infrastructure.persistence.repository.JpaDailyCaptureAuditRepository
import com.fitlake.daily.infrastructure.persistence.repository.JpaDailyCaptureRepository
import com.fitlake.daily.infrastructure.persistence.repository.JpaDailyDayRepository
import com.fitlake.daily.infrastructure.persistence.repository.JpaDailyMetricsRepository
import com.fitlake.food.domain.DefaultServing
import com.fitlake.food.domain.FoodUnit
import com.fitlake.food.domain.NutrientValues
import com.fitlake.food.domain.NutritionBasis
import com.fitlake.food.domain.NutritionSource
import com.fitlake.food.domain.NutritionSourceType
import com.fitlake.food.domain.UnitConversions
import com.fitlake.food.domain.UserFood
import com.fitlake.food.domain.UserFoodDefinition
import com.fitlake.food.infrastructure.persistence.mapper.UserFoodPersistenceMapper
import com.fitlake.food.infrastructure.persistence.repository.JpaUserFoodRepositoryAdapter
import com.fitlake.shared.application.TransactionExecutor
import com.fitlake.user.domain.UserAccount
import com.fitlake.user.domain.UserId
import com.fitlake.user.infrastructure.SpringTransactionExecutor
import com.fitlake.user.infrastructure.persistence.mapper.UserPersistenceMapper
import com.fitlake.user.infrastructure.persistence.repository.JpaUserAccountRepositoryAdapter
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

@DataJpaTest(
	properties = [
		"spring.jpa.hibernate.ddl-auto=validate",
		"spring.flyway.enabled=true",
	],
)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers(disabledWithoutDocker = true)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import(
	JacksonAutoConfiguration::class,
	DailyPersistenceMapper::class,
	DailyCaptureAuditPersistenceMapper::class,
	JpaDailyDayRepository::class,
	JpaDailyCaptureRepository::class,
	JpaDailyMetricsRepository::class,
	JpaDailyCaptureAuditRepository::class,
	UserFoodPersistenceMapper::class,
	JpaUserFoodRepositoryAdapter::class,
	UserPersistenceMapper::class,
	JpaUserAccountRepositoryAdapter::class,
)
class DailyManualCatalogIntegrationTest @Autowired constructor(
	private val days: JpaDailyDayRepository,
	private val captures: JpaDailyCaptureRepository,
	private val metrics: JpaDailyMetricsRepository,
	private val audits: JpaDailyCaptureAuditRepository,
	private val foods: JpaUserFoodRepositoryAdapter,
	private val users: JpaUserAccountRepositoryAdapter,
	private val jdbcTemplate: JdbcTemplate,
	transactionManager: PlatformTransactionManager,
) {
	private val now = Instant.parse("2026-07-31T12:00:00Z")
	private val clock = Clock.fixed(now, ZoneId.of("UTC"))
	private val transactions: TransactionExecutor = SpringTransactionExecutor(transactionManager)
	private val manualService = DailyManualCaptureService(
		dayRepository = days,
		captureRepository = captures,
		metricsRepository = metrics,
		auditRepository = audits,
		contentFactory = DailyCaptureContentFactory(CatalogDailyUserFoodLookupAdapter(foods)),
		transactionExecutor = transactions,
		clock = clock,
	)
	private var userA: UserId = UserId(UUID.randomUUID())
	private var userB: UserId = UserId(UUID.randomUUID())
	private lateinit var ownedFood: UserFood
	private lateinit var foreignFood: UserFood
	private lateinit var deletedFood: UserFood

	@BeforeEach
	fun setUpCatalog() {
		userA = createUser("daily-catalog-a")
		userB = createUser("daily-catalog-b")
		ownedFood = createFood(userA, "Complete Greek yogurt", "Example Brand")
		foreignFood = createFood(userB, "Foreign Greek yogurt", "Foreign Brand")
		deletedFood = createFood(userA, "Deleted Greek yogurt", "Old Brand")
		deletedFood = transactions.required {
			foods.save(deletedFood.softDelete(now.plusSeconds(1)))
		}
	}

	@Test
	fun `owned active catalog food creates calculated v2 snapshot and round trips through JSONB`() {
		val date = LocalDate.parse("2026-08-20")

		val created = manualService.create(userA, date, captureInput(ownedFood.foodId.value))
		val loaded = assertNotNull(captures.findByIdAndUserId(created.captureId, userA))
		val entry = loaded.payload.entries.single()
		val item = entry.items.single()
		val snapshot = assertNotNull(item.userFoodSnapshot)

		assertEquals(DAILY_CAPTURE_SCHEMA_VERSION, loaded.payload.schemaVersion)
		assertEquals(DailyCaptureType.FOOD, loaded.captureType)
		assertEquals(DailyCaptureStatus.OPEN, loaded.status)
		assertEquals(DailyCaptureActor.USER_UI, loaded.createdBy)
		assertNull(loaded.sourceEventId)
		assertEquals(DailyCaptureEntryType.FOOD, entry.type)
		assertEquals(DailyMealType.BREAKFAST, entry.mealType)
		assertEquals(ownedFood.foodId.value, item.userFoodId)
		assertEquals("Complete Greek yogurt", item.displayName)
		assertEquals("Example Brand", item.brand)
		assertDecimal("1", item.enteredQuantity.amount)
		assertEquals(DailyFoodQuantityUnit.DEFAULT_SERVING, item.enteredQuantity.unit)
		assertDecimal("170", item.resolvedQuantity.amount)
		assertEquals(DailyResolvedFoodUnit.GRAM, item.resolvedQuantity.unit)

		assertDecimal("100", snapshot.nutritionBasis.amount)
		assertEquals(DailyFoodSnapshotUnit.GRAM, snapshot.nutritionBasis.unit)
		assertDecimal("62", snapshot.nutrientsPerBasis.caloriesKcal)
		assertDecimal("9.5", snapshot.nutrientsPerBasis.proteinGrams)
		assertDecimal("4.1", snapshot.nutrientsPerBasis.carbohydratesGrams)
		assertDecimal("0.2", snapshot.nutrientsPerBasis.fatGrams)
		assertDecimal("1.3", snapshot.nutrientsPerBasis.fiberGrams)
		assertDecimal("3.25", snapshot.nutrientsPerBasis.sugarsGrams)
		assertDecimal("0.1", snapshot.nutrientsPerBasis.saturatedFatGrams)
		assertDecimal("42.5", snapshot.nutrientsPerBasis.sodiumMilligrams)
		assertDecimal("0.08", snapshot.nutrientsPerBasis.saltGrams)
		assertDecimal("170", snapshot.defaultServing?.amount)
		assertEquals(DailyFoodSnapshotUnit.GRAM, snapshot.defaultServing?.unit)
		assertDecimal("12.5", snapshot.conversions.gramsPerPiece)
		assertDecimal("50", snapshot.conversions.gramsPerServing)
		assertEquals("PRODUCT_LABEL", snapshot.nutritionSource.originalSourceType)
		assertEquals(false, snapshot.nutritionSource.estimated)
		assertEquals(ownedFood.version, snapshot.userFoodVersion)
		assertEquals(ownedFood.updatedAt, snapshot.userFoodUpdatedAt)

		assertDecimal("105.4", item.calculatedNutrition.caloriesKcal)
		assertDecimal("16.15", item.calculatedNutrition.proteinGrams)
		assertDecimal("6.97", item.calculatedNutrition.carbohydratesGrams)
		assertDecimal("0.34", item.calculatedNutrition.fatGrams)
		assertDecimal("2.21", item.calculatedNutrition.fiberGrams)
		assertDecimal("5.525", item.calculatedNutrition.sugarsGrams)
		assertDecimal("0.17", item.calculatedNutrition.saturatedFatGrams)
		assertDecimal("72.25", item.calculatedNutrition.sodiumMilligrams)
		assertDecimal("0.136", item.calculatedNutrition.saltGrams)
		val nutritionTotal = assertNotNull(entry.nutritionTotal)
		assertDecimal("105.4", nutritionTotal.caloriesKcal)
		assertDecimal("16.15", nutritionTotal.proteinGrams)
		assertDecimal("6.97", nutritionTotal.carbohydratesGrams)
		assertDecimal("0.34", nutritionTotal.fatGrams)
		assertDecimal("2.21", nutritionTotal.fiberGrams)
		assertDecimal("5.525", nutritionTotal.sugarsGrams)
		assertDecimal("0.17", nutritionTotal.saturatedFatGrams)
		assertDecimal("72.25", nutritionTotal.sodiumMilligrams)
		assertDecimal("0.136", nutritionTotal.saltGrams)
		assertEquals(
			DAILY_CAPTURE_SCHEMA_VERSION,
			jdbcTemplate.queryForObject(
				"SELECT (payload ->> 'schemaVersion')::integer FROM daily_capture WHERE capture_id = ?",
				Int::class.java,
				created.captureId.value,
			),
		)
	}

	@Test
	fun `foreign and deleted catalog foods are indistinguishably unavailable`() {
		val foreignDate = LocalDate.parse("2026-08-21")
		val deletedDate = LocalDate.parse("2026-08-22")

		val foreignFailure = assertFailsWith<DailyNotFoundException> {
			manualService.create(userA, foreignDate, captureInput(foreignFood.foodId.value))
		}
		val deletedFailure = assertFailsWith<DailyNotFoundException> {
			manualService.create(userA, deletedDate, captureInput(deletedFood.foodId.value))
		}

		assertEquals("Personal food was not found", foreignFailure.message)
		assertEquals(foreignFailure.message, deletedFailure.message)
		assertNull(days.findByUserIdAndDate(userA, foreignDate))
		assertNull(days.findByUserIdAndDate(userA, deletedDate))
		assertEquals(0, captureCount(userA))
	}

	@Test
	fun `invalid item after a valid catalog item rolls back both day and capture`() {
		val date = LocalDate.parse("2026-08-23")
		val input = DailyCaptureContentInput(
			entries = listOf(
				foodEntry(
					ownedFood.foodId.value,
					foreignFood.foodId.value,
				),
			),
		)

		assertFailsWith<DailyNotFoundException> {
			manualService.create(userA, date, input)
		}

		assertNull(days.findByUserIdAndDate(userA, date))
		assertEquals(0, dayCount(userA, date))
		assertEquals(0, captureCount(userA))
	}

	private fun captureInput(foodId: UUID): DailyCaptureContentInput = DailyCaptureContentInput(
		entries = listOf(foodEntry(foodId)),
	)

	private fun foodEntry(vararg foodIds: UUID): DailyCaptureEntryInput = DailyCaptureEntryInput(
		entryId = null,
		type = DailyCaptureEntryType.FOOD,
		mealType = DailyMealType.BREAKFAST,
		items = foodIds.map { foodId ->
			DailyFoodItemInput(
				itemId = null,
				sourceType = DailyFoodItemSourceType.USER_FOOD,
				userFoodId = foodId,
				quantity = DailyEnteredFoodQuantityInput(
					amount = BigDecimal.ONE,
					unit = DailyFoodQuantityUnit.DEFAULT_SERVING,
				),
			)
		},
	)

	private fun createUser(prefix: String): UserId = transactions.required {
		val userId = UserId(UUID.randomUUID())
		users.save(
			UserAccount(
				userId = userId,
				email = "$prefix-${userId.value}@example.com",
				displayName = "Daily catalog integration",
				timezone = ZoneId.of("Europe/Rome"),
				createdAt = now,
				updatedAt = now,
			),
		)
		userId
	}

	private fun createFood(userId: UserId, name: String, brand: String): UserFood = transactions.required {
		foods.save(
			UserFood.create(
				userId = userId,
				definition = UserFoodDefinition.from(
					name = name,
					brand = brand,
					barcode = null,
					description = "Complete product-label definition",
					aliases = emptyList(),
					nutritionBasis = NutritionBasis(BigDecimal("100"), FoodUnit.GRAM),
					nutrients = NutrientValues(
						caloriesKcal = BigDecimal("62"),
						proteinGrams = BigDecimal("9.5"),
						carbohydratesGrams = BigDecimal("4.1"),
						fatGrams = BigDecimal("0.2"),
						fiberGrams = BigDecimal("1.3"),
						sugarsGrams = BigDecimal("3.25"),
						saturatedFatGrams = BigDecimal("0.1"),
						sodiumMilligrams = BigDecimal("42.5"),
						saltGrams = BigDecimal("0.08"),
					),
					defaultServing = DefaultServing(BigDecimal("170"), FoodUnit.GRAM),
					conversions = UnitConversions(
						gramsPerPiece = BigDecimal("12.5"),
						gramsPerServing = BigDecimal("50"),
					),
					source = NutritionSource(
						type = NutritionSourceType.PRODUCT_LABEL,
						notes = "Copied from the package",
						copiedAt = LocalDate.parse("2026-07-30"),
					),
				),
				at = now,
			),
		)
	}

	private fun dayCount(userId: UserId, date: LocalDate): Int = requireNotNull(
		jdbcTemplate.queryForObject(
			"SELECT COUNT(*) FROM daily_day WHERE user_id = ? AND day_date = ?",
			Int::class.java,
			userId.value,
			date,
		),
	)

	private fun captureCount(userId: UserId): Int = requireNotNull(
		jdbcTemplate.queryForObject(
			"SELECT COUNT(*) FROM daily_capture WHERE user_id = ?",
			Int::class.java,
			userId.value,
		),
	)

	private fun assertDecimal(expected: String, actual: BigDecimal?) {
		val value = assertNotNull(actual)
		assertEquals(0, BigDecimal(expected).compareTo(value))
	}

	companion object {
		@Container
		@JvmStatic
		val postgres = PostgreSQLContainer("postgres:16-alpine")

		@DynamicPropertySource
		@JvmStatic
		fun postgresProperties(registry: DynamicPropertyRegistry) {
			registry.add("spring.datasource.url", postgres::getJdbcUrl)
			registry.add("spring.datasource.username", postgres::getUsername)
			registry.add("spring.datasource.password", postgres::getPassword)
		}
	}
}
