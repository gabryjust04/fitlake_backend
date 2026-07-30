package com.fitlake.daily.infrastructure.persistence

import com.fitlake.daily.application.capture.CaptureConfirmationService
import com.fitlake.daily.application.capture.DailyCaptureInput
import com.fitlake.daily.application.capture.DailyCaptureService
import com.fitlake.daily.application.capture.DailyFieldsInput
import com.fitlake.daily.application.capture.DailyPayloadFactory
import com.fitlake.daily.application.capture.MealInput
import com.fitlake.daily.application.capture.MealItemInput
import com.fitlake.daily.application.finalization.DailyFinalizationService
import com.fitlake.daily.application.finalization.DailyMetricsProjectionService
import com.fitlake.daily.domain.capture.DailyCaptureStatus
import com.fitlake.daily.domain.capture.DailyCaptureType
import com.fitlake.daily.infrastructure.persistence.mapper.DailyPersistenceMapper
import com.fitlake.daily.infrastructure.persistence.repository.JpaDailyCaptureRepository
import com.fitlake.daily.infrastructure.persistence.repository.JpaDailyDayRepository
import com.fitlake.daily.infrastructure.persistence.repository.JpaDailyMetricsRepository
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

@DataJpaTest(
	properties = [
		"spring.jpa.hibernate.ddl-auto=validate",
		"spring.flyway.enabled=true",
	],
)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers(disabledWithoutDocker = true)
@Import(
	JacksonAutoConfiguration::class,
	DailyPersistenceMapper::class,
	JpaDailyDayRepository::class,
	JpaDailyCaptureRepository::class,
	JpaDailyMetricsRepository::class,
	UserPersistenceMapper::class,
	JpaUserAccountRepositoryAdapter::class,
)
class DailyPersistenceIntegrationTest @Autowired constructor(
	private val days: JpaDailyDayRepository,
	private val captures: JpaDailyCaptureRepository,
	private val metrics: JpaDailyMetricsRepository,
	private val users: JpaUserAccountRepositoryAdapter,
	private val jdbcTemplate: JdbcTemplate,
	transactionManager: PlatformTransactionManager,
) {
	private val now = Instant.parse("2026-07-28T10:00:00Z")
	private val date = LocalDate.parse("2026-07-28")
	private val clock = Clock.fixed(now, ZoneId.of("UTC"))
	private val transactions: TransactionExecutor = SpringTransactionExecutor(transactionManager)
	private val payloadFactory = DailyPayloadFactory()
	private val captureService = DailyCaptureService(days, captures, payloadFactory, transactions, clock)
	private val confirmationService = CaptureConfirmationService(
		days,
		captures,
		captureService,
		transactions,
		clock,
	)
	private val finalizationService = DailyFinalizationService(
		days,
		captures,
		metrics,
		DailyMetricsProjectionService(),
		transactions,
		clock,
	)
	private var userId: UserId = UserId(UUID.randomUUID())

	@BeforeEach
	fun createUser() {
		userId = UserId(UUID.randomUUID())
		users.save(
			UserAccount(
				userId = userId,
				email = "daily@example.com",
				displayName = "Daily test",
				timezone = ZoneId.of("Europe/Rome"),
				createdAt = now,
				updatedAt = now,
			),
		)
	}

	@Test
	fun `capture JSONB round trip preserves payload ids and timestamps`() {
		val created = captureService.create(userId, date, foodInput())

		val loaded = captures.findById(created.captureId)!!

		assertEquals(created.captureId, loaded.captureId)
		assertEquals("breakfast", loaded.payload.meals.single().mealTempId)
		assertEquals("oats", loaded.payload.meals.single().items.single().itemTempId)
		assertEquals(now, loaded.createdAt)
		assertEquals(
			"object",
			jdbcTemplate.queryForObject(
				"SELECT jsonb_typeof(payload) FROM daily_capture WHERE capture_id = ?",
				String::class.java,
				created.captureId.value,
			),
		)
	}

	@Test
	fun `accepted captures finalize into persisted metrics`() {
		val food = captureService.create(userId, date, foodInput())
		val fields = captureService.create(
			userId,
			date,
			DailyCaptureInput(
				type = DailyCaptureType.DAILY_FIELDS,
				fields = DailyFieldsInput(bodyWeightKg = BigDecimal("78.4")),
			),
		)
		confirmationService.accept(userId, food.captureId)
		confirmationService.accept(userId, fields.captureId)

		val finalized = finalizationService.finalizeDay(userId, date)
		val persisted = metrics.findByDayId(finalized.dayId)!!

		assertEquals(150, persisted.totalCalories)
		assertEquals(BigDecimal("78.4"), persisted.bodyWeightKg)
		assertEquals(2, persisted.generatedFromCaptureIds.size)
		assertEquals(DailyCaptureStatus.ACCEPTED, captures.findById(food.captureId)?.status)
		assertEquals(
			"array",
			jdbcTemplate.queryForObject(
				"SELECT jsonb_typeof(food_log) FROM daily_metrics WHERE day_id = ?",
				String::class.java,
				finalized.dayId.value,
			),
		)
	}

	private fun foodInput() = DailyCaptureInput(
		type = DailyCaptureType.FOOD,
		meals = listOf(
			MealInput(
				mealTempId = "breakfast",
				mealName = "colazione",
				items = listOf(
					MealItemInput(
						itemTempId = "oats",
						foodName = "avena",
						quantity = BigDecimal("40"),
						unit = "g",
						calories = 150,
						proteinG = BigDecimal("5"),
						carbsG = BigDecimal("27"),
						fatG = BigDecimal("3"),
					),
				),
			),
		),
	)

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
