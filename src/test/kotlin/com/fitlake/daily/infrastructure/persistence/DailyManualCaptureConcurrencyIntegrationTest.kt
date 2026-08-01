package com.fitlake.daily.infrastructure.persistence

import com.fitlake.daily.application.DailyConflictException
import com.fitlake.daily.application.capture.DailyCaptureContentFactory
import com.fitlake.daily.application.capture.DailyCaptureContentInput
import com.fitlake.daily.application.capture.DailyCaptureEntryInput
import com.fitlake.daily.application.capture.DailyManualCaptureService
import com.fitlake.daily.application.port.DailyCaptureAuditRepository
import com.fitlake.daily.application.port.DailyCaptureRepository
import com.fitlake.daily.application.port.DailyDayRepository
import com.fitlake.daily.application.port.DailyMetricsRepository
import com.fitlake.daily.application.port.DailyOwnedUserFood
import com.fitlake.daily.application.port.DailyUserFoodLookupPort
import com.fitlake.daily.domain.audit.DailyCaptureAuditAction
import com.fitlake.daily.domain.capture.DailyCapture
import com.fitlake.daily.domain.capture.DailyCaptureEntryType
import com.fitlake.daily.domain.capture.DailyScalarUnit
import com.fitlake.daily.infrastructure.persistence.mapper.DailyCaptureAuditPersistenceMapper
import com.fitlake.daily.infrastructure.persistence.mapper.DailyPersistenceMapper
import com.fitlake.daily.infrastructure.persistence.repository.JpaDailyCaptureAuditRepository
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
import org.springframework.dao.OptimisticLockingFailureException
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
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
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
	JpaDailyCaptureAuditRepository::class,
	JpaDailyMetricsRepository::class,
	UserPersistenceMapper::class,
	JpaUserAccountRepositoryAdapter::class,
)
class DailyManualCaptureConcurrencyIntegrationTest @Autowired constructor(
	private val days: DailyDayRepository,
	private val captures: DailyCaptureRepository,
	private val metrics: DailyMetricsRepository,
	private val audits: DailyCaptureAuditRepository,
	private val users: JpaUserAccountRepositoryAdapter,
	transactionManager: PlatformTransactionManager,
) {
	private val transactions: TransactionExecutor = SpringTransactionExecutor(transactionManager)
	private val now = Instant.parse("2026-07-31T12:00:00Z")
	private val date = LocalDate.parse("2026-07-31")
	private val service = DailyManualCaptureService(
		dayRepository = days,
		captureRepository = captures,
		metricsRepository = metrics,
		auditRepository = audits,
		contentFactory = DailyCaptureContentFactory(NoFoodLookup),
		transactionExecutor = transactions,
		clock = Clock.fixed(now, ZoneOffset.UTC),
	)
	private var userId: UserId = UserId(UUID.randomUUID())

	@BeforeEach
	fun createUser() {
		userId = UserId(UUID.randomUUID())
		users.save(
			UserAccount(
				userId = userId,
				email = "manual-concurrency-${userId.value}@example.com",
				displayName = "Manual concurrency test",
				timezone = ZoneOffset.UTC,
				createdAt = now,
				updatedAt = now,
			),
		)
	}

	@Test
	fun `two full content replacements with the same version produce one winner and one audit`() {
		val created = service.create(userId, date, weightContent("78"))
		val entryId = created.payload.entries.single().entryId
		val ready = CountDownLatch(2)
		val start = CountDownLatch(1)
		val executor = Executors.newFixedThreadPool(2)

		val first = executor.submit<EditAttempt> {
			attemptEdit(created, "79", entryId, "concurrent-edit-1", ready, start)
		}
		val second = executor.submit<EditAttempt> {
			attemptEdit(created, "80", entryId, "concurrent-edit-2", ready, start)
		}

		val attempts = try {
			assertTrue(ready.await(10, TimeUnit.SECONDS), "Both edit workers must be ready")
			start.countDown()
			listOf(
				first.get(30, TimeUnit.SECONDS),
				second.get(30, TimeUnit.SECONDS),
			)
		} finally {
			start.countDown()
			executor.shutdownNow()
			assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS), "Edit workers must terminate")
		}

		val successes = attempts.filter { it.capture != null }
		val failures = attempts.filter { it.failure != null }
		assertEquals(1, successes.size)
		assertEquals(1, failures.size)
		assertTrue(
			failures.single().failure is DailyConflictException ||
				failures.single().failure is OptimisticLockingFailureException,
			"The losing edit must be reported as an optimistic conflict",
		)

		val winner = successes.single()
		val winnerCapture = assertNotNull(winner.capture)
		val persisted = transactions.required {
			assertNotNull(captures.findByIdAndUserId(created.captureId, userId))
		}
		val persistedAudits = transactions.required {
			audits.findAllByCaptureIdAndUserId(created.captureId, userId)
		}

		assertEquals(created.version + 1, persisted.version)
		assertEquals(winnerCapture.version, persisted.version)
		assertEquals(winnerCapture.payload, persisted.payload)
		assertEquals(1, persistedAudits.size)

		val audit = persistedAudits.single()
		assertEquals(DailyCaptureAuditAction.UI_EDIT, audit.action)
		assertEquals(created.version, audit.oldVersion)
		assertEquals(persisted.version, audit.newVersion)
		assertEquals(created.payload, audit.oldPayload)
		assertEquals(persisted.payload, audit.newPayload)
		assertEquals(winner.requestId, audit.requestId)
	}

	private fun attemptEdit(
		created: DailyCapture,
		value: String,
		entryId: UUID,
		requestId: String,
		ready: CountDownLatch,
		start: CountDownLatch,
	): EditAttempt {
		ready.countDown()
		return try {
			check(start.await(10, TimeUnit.SECONDS)) { "Concurrent edit start signal timed out" }
			EditAttempt(
				requestId = requestId,
				capture = service.replace(
					userId = userId,
					captureId = created.captureId,
					expectedVersion = created.version,
					input = weightContent(value, entryId),
					requestId = requestId,
				),
			)
		} catch (exception: Throwable) {
			EditAttempt(requestId = requestId, failure = exception)
		}
	}

	private fun weightContent(value: String, entryId: UUID? = null) = DailyCaptureContentInput(
		entries = listOf(
			DailyCaptureEntryInput(
				entryId = entryId,
				type = DailyCaptureEntryType.WEIGHT,
				value = BigDecimal(value),
				unit = DailyScalarUnit.KILOGRAM,
			),
		),
	)

	private data class EditAttempt(
		val requestId: String,
		val capture: DailyCapture? = null,
		val failure: Throwable? = null,
	)

	private object NoFoodLookup : DailyUserFoodLookupPort {
		override fun findActiveOwnedFood(userId: UserId, userFoodId: UUID): DailyOwnedUserFood? = null
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
