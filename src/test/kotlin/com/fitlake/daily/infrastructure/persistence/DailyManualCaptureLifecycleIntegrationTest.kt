package com.fitlake.daily.infrastructure.persistence

import com.fitlake.daily.application.DailyConflictException
import com.fitlake.daily.application.capture.CaptureConfirmationService
import com.fitlake.daily.application.capture.DailyCaptureContentFactory
import com.fitlake.daily.application.capture.DailyCaptureContentInput
import com.fitlake.daily.application.capture.DailyCaptureEntryInput
import com.fitlake.daily.application.capture.DailyCaptureService
import com.fitlake.daily.application.capture.DailyEnteredFoodQuantityInput
import com.fitlake.daily.application.capture.DailyFoodItemInput
import com.fitlake.daily.application.capture.DailyManualCaptureService
import com.fitlake.daily.application.finalization.DailyDayReopeningService
import com.fitlake.daily.application.finalization.DailyFinalizationService
import com.fitlake.daily.application.finalization.DailyMetricsProjectionService
import com.fitlake.daily.application.port.DailyCaptureAuditRepository
import com.fitlake.daily.application.port.DailyCaptureRepository
import com.fitlake.daily.application.port.DailyDayRepository
import com.fitlake.daily.application.port.DailyMetricsRepository
import com.fitlake.daily.domain.audit.DailyCaptureAuditAction
import com.fitlake.daily.domain.capture.DailyCaptureEntryType
import com.fitlake.daily.domain.capture.DailyCaptureStatus
import com.fitlake.daily.domain.capture.DailyFoodItemSourceType
import com.fitlake.daily.domain.capture.DailyFoodQuantityUnit
import com.fitlake.daily.domain.capture.DailyMealType
import com.fitlake.daily.domain.common.DailyDayStatus
import com.fitlake.daily.infrastructure.food.CatalogDailyUserFoodLookupAdapter
import com.fitlake.daily.infrastructure.persistence.mapper.DailyCaptureAuditPersistenceMapper
import com.fitlake.daily.infrastructure.persistence.mapper.DailyPersistenceMapper
import com.fitlake.daily.infrastructure.persistence.repository.JpaDailyCaptureAuditRepository
import com.fitlake.daily.infrastructure.persistence.repository.JpaDailyCaptureRepository
import com.fitlake.daily.infrastructure.persistence.repository.JpaDailyDayRepository
import com.fitlake.daily.infrastructure.persistence.repository.JpaDailyMetricsRepository
import com.fitlake.food.application.NutrientValuesInput
import com.fitlake.food.application.NutritionBasisInput
import com.fitlake.food.application.NutritionSourceInput
import com.fitlake.food.application.UserFoodDefinitionInput
import com.fitlake.food.application.UserFoodService
import com.fitlake.food.domain.FoodUnit
import com.fitlake.food.domain.NutritionSourceType
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
import kotlin.test.assertTrue

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
class DailyManualCaptureLifecycleIntegrationTest @Autowired constructor(
	private val days: DailyDayRepository,
	private val captures: DailyCaptureRepository,
	private val metrics: DailyMetricsRepository,
	private val audits: DailyCaptureAuditRepository,
	private val foods: JpaUserFoodRepositoryAdapter,
	private val users: JpaUserAccountRepositoryAdapter,
	private val jdbcTemplate: JdbcTemplate,
	transactionManager: PlatformTransactionManager,
) {
	private val now = Instant.parse("2026-07-31T12:00:00Z")
	private val date = LocalDate.parse("2026-07-31")
	private val clock = Clock.fixed(now, ZoneId.of("UTC"))
	private val transactions: TransactionExecutor = SpringTransactionExecutor(transactionManager)
	private val catalogService = UserFoodService(foods, transactions, clock)
	private val contentFactory = DailyCaptureContentFactory(CatalogDailyUserFoodLookupAdapter(foods))
	private val captureService = DailyCaptureService(
		dayRepository = days,
		captureRepository = captures,
		transactionExecutor = transactions,
		clock = clock,
	)
	private val manualCaptureService = DailyManualCaptureService(
		dayRepository = days,
		captureRepository = captures,
		metricsRepository = metrics,
		auditRepository = audits,
		contentFactory = contentFactory,
		transactionExecutor = transactions,
		clock = clock,
	)
	private val confirmationService = CaptureConfirmationService(
		dayRepository = days,
		captureRepository = captures,
		auditRepository = audits,
		captureService = captureService,
		transactionExecutor = transactions,
		clock = clock,
	)
	private val finalizationService = DailyFinalizationService(
		dayRepository = days,
		captureRepository = captures,
		metricsRepository = metrics,
		projectionService = DailyMetricsProjectionService(),
		transactionExecutor = transactions,
		clock = clock,
	)
	private val reopeningService = DailyDayReopeningService(
		dayRepository = days,
		metricsRepository = metrics,
		transactionExecutor = transactions,
		clock = clock,
	)
	private var userId = UserId(UUID.randomUUID())

	@BeforeEach
	fun createUser() {
		userId = UserId(UUID.randomUUID())
		users.save(
			UserAccount(
				userId = userId,
				email = "daily-lifecycle-${userId.value}@example.com",
				displayName = "Daily lifecycle test",
				timezone = ZoneId.of("Europe/Rome"),
				createdAt = now,
				updatedAt = now,
			),
		)
	}

	@Test
	fun `accepted v2 capture is immutable after finalization editable after reopen and metrics are upserted`() {
		val food = catalogService.create(
			userId,
			UserFoodDefinitionInput(
				name = "Lifecycle yogurt",
				brand = "FitLake test",
				nutritionBasis = NutritionBasisInput(BigDecimal("100"), FoodUnit.GRAM),
				nutrients = NutrientValuesInput(
					caloriesKcal = BigDecimal("62"),
					proteinGrams = BigDecimal("9.5"),
					carbohydratesGrams = BigDecimal("4.1"),
					fatGrams = BigDecimal("0.2"),
				),
				source = NutritionSourceInput(NutritionSourceType.PRODUCT_LABEL),
			),
		)
		val created = manualCaptureService.create(userId, date, foodContent(food.foodId.value, "100"))
		val accepted = confirmationService.accept(userId, created.captureId)
		val originalEntry = accepted.payload.entries.single()
		val originalItem = originalEntry.items.single()

		val edited = manualCaptureService.replace(
			userId = userId,
			captureId = accepted.captureId,
			expectedVersion = accepted.version,
			input = foodContent(
				foodId = food.foodId.value,
				amount = "150",
				entryId = originalEntry.entryId,
				itemId = originalItem.itemId,
			),
			requestId = "accepted-before-finalization",
		)

		assertEquals(DailyCaptureStatus.ACCEPTED, edited.status)
		assertTrue(edited.version > accepted.version)
		assertDecimal("93.000000", edited.payload.entries.single().items.single().calculatedNutrition.caloriesKcal)
		val captureAudits = audits.findAllByCaptureIdAndUserId(edited.captureId, userId)
		assertEquals(3, captureAudits.size)
		assertEquals(
			setOf(
				DailyCaptureAuditAction.CREATE,
				DailyCaptureAuditAction.ACCEPT,
				DailyCaptureAuditAction.UI_EDIT,
			),
			captureAudits.map { it.action }.toSet(),
		)

		val finalized = finalizationService.finalizeDay(userId, date)
		val persistedMetrics = assertNotNull(metrics.findByDayId(finalized.dayId))
		val persistedDay = assertNotNull(days.findById(finalized.dayId))
		val loggedItem = persistedMetrics.foodLog.single().items.single()

		assertEquals(DailyDayStatus.CONFIRMED, finalized.status)
		assertEquals(DailyDayStatus.CONFIRMED, persistedDay.status)
		assertDecimal("93.000000", finalized.totalCalories)
		assertDecimal("93.000000", persistedMetrics.totalCalories)
		assertDecimal("150.000000", loggedItem.quantity)
		assertDecimal("93.000000", loggedItem.calories)
		assertEquals("Lifecycle yogurt", loggedItem.foodName)
		assertEquals(
			1,
			jdbcTemplate.queryForObject(
				"SELECT jsonb_array_length(food_log) FROM daily_metrics WHERE day_id = ?",
				Int::class.java,
				finalized.dayId.value,
			),
		)

		val beforeRejectedPut = assertNotNull(captures.findById(edited.captureId))
		val auditsBeforeRejectedPut = audits.findAllByCaptureIdAndUserId(edited.captureId, userId)
		assertFailsWith<DailyConflictException> {
			manualCaptureService.replace(
				userId = userId,
				captureId = edited.captureId,
				expectedVersion = edited.version,
				input = foodContent(
					foodId = food.foodId.value,
					amount = "200",
					entryId = originalEntry.entryId,
					itemId = originalItem.itemId,
				),
				requestId = "rejected-after-finalization",
			)
		}

		val afterRejectedPut = assertNotNull(captures.findById(edited.captureId))
		assertEquals(DailyCaptureStatus.ACCEPTED, afterRejectedPut.status)
		assertEquals(beforeRejectedPut.payload, afterRejectedPut.payload)
		assertEquals(beforeRejectedPut.version, afterRejectedPut.version)
		assertEquals(auditsBeforeRejectedPut, audits.findAllByCaptureIdAndUserId(edited.captureId, userId))

		val reopened = reopeningService.reopenDay(userId, date)
		assertEquals(DailyDayStatus.REOPENED, reopened.status)
		assertEquals(DailyDayStatus.REOPENED, metrics.findByDayId(finalized.dayId)?.status)

		val changedWhileReopened = manualCaptureService.replace(
			userId = userId,
			captureId = edited.captureId,
			expectedVersion = afterRejectedPut.version,
			input = foodContent(
				foodId = food.foodId.value,
				amount = "200",
				entryId = originalEntry.entryId,
				itemId = originalItem.itemId,
			),
			requestId = "accepted-after-reopen",
		)
		assertEquals(DailyCaptureStatus.ACCEPTED, changedWhileReopened.status)

		val refinalized = finalizationService.finalizeDay(userId, date)
		val refinalizedAgain = finalizationService.finalizeDay(userId, date)
		assertEquals(refinalized, refinalizedAgain)
		assertEquals(DailyDayStatus.CONFIRMED, refinalized.status)
		assertDecimal("124.000000", refinalized.totalCalories)
		assertDecimal("200.000000", refinalized.foodLog.single().items.single().quantity)
		assertEquals(finalized.createdAt, refinalized.createdAt)
		assertNotNull(refinalized.recalculatedAt)
		assertEquals(
			1L,
			jdbcTemplate.queryForObject(
				"SELECT count(*) FROM daily_metrics WHERE day_id = ?",
				Long::class.java,
				finalized.dayId.value,
			),
		)
	}

	private fun foodContent(
		foodId: UUID,
		amount: String,
		entryId: UUID? = null,
		itemId: UUID? = null,
	): DailyCaptureContentInput = DailyCaptureContentInput(
		entries = listOf(
			DailyCaptureEntryInput(
				entryId = entryId,
				type = DailyCaptureEntryType.FOOD,
				mealType = DailyMealType.LUNCH,
				items = listOf(
					DailyFoodItemInput(
						itemId = itemId,
						sourceType = DailyFoodItemSourceType.USER_FOOD,
						userFoodId = foodId,
						quantity = DailyEnteredFoodQuantityInput(
							amount = BigDecimal(amount),
							unit = DailyFoodQuantityUnit.GRAM,
						),
					),
				),
			),
		),
	)

	private fun assertDecimal(expected: String, actual: BigDecimal?) {
		assertNotNull(actual)
		assertEquals(0, BigDecimal(expected).compareTo(actual))
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
