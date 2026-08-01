package com.fitlake.daily.infrastructure.persistence

import com.fitlake.daily.application.ai.AiCaptureProposal
import com.fitlake.daily.application.ai.AiFoodItemProposal
import com.fitlake.daily.application.ai.AiMealProposal
import com.fitlake.daily.application.ai.AiNoOpProposal
import com.fitlake.daily.application.ai.DailyAiAuditService
import com.fitlake.daily.application.ai.DailyAiCaptureProposalFactory
import com.fitlake.daily.application.ai.DailyAiInterpreter
import com.fitlake.daily.application.ai.DailyAiMessageService
import com.fitlake.daily.application.ai.DailyAiOperationInProgressException
import com.fitlake.daily.application.ai.DailyAiPersistenceException
import com.fitlake.daily.application.ai.DailyAiPreparation
import com.fitlake.daily.application.ai.DailyAiProviderMetadata
import com.fitlake.daily.application.ai.DailyAiResult
import com.fitlake.daily.application.ai.DailyAiTerminalService
import com.fitlake.daily.application.capture.DailyCaptureService
import com.fitlake.daily.application.port.AiInterpretationLogRepository
import com.fitlake.daily.application.port.DailyAiUserFoodMatchPort
import com.fitlake.daily.application.port.DailyAiUserFoodMatchResult
import com.fitlake.daily.domain.ai.AiInterpretationLog
import com.fitlake.daily.domain.ai.AiInterpretationStatus
import com.fitlake.daily.domain.capture.DailyCaptureStatus
import com.fitlake.daily.domain.capture.DailyCaptureType
import com.fitlake.daily.domain.capture.DailyFoodItemSourceType
import com.fitlake.daily.domain.capture.DAILY_CAPTURE_SCHEMA_VERSION
import com.fitlake.daily.domain.inbox.DailyInboxProcessingStatus
import com.fitlake.daily.infrastructure.persistence.mapper.DailyAiPersistenceMapper
import com.fitlake.daily.infrastructure.persistence.mapper.DailyPersistenceMapper
import com.fitlake.daily.infrastructure.persistence.repository.JpaAiInterpretationLogRepository
import com.fitlake.daily.infrastructure.persistence.repository.JpaDailyCaptureRepository
import com.fitlake.daily.infrastructure.persistence.repository.JpaDailyDayRepository
import com.fitlake.daily.infrastructure.persistence.repository.JpaDailyInboxEventRepository
import com.fitlake.daily.infrastructure.persistence.repository.SpringDataDailyCaptureRepository
import com.fitlake.daily.infrastructure.persistence.repository.SpringDataDailyInboxEventRepository
import com.fitlake.shared.application.TransactionExecutor
import com.fitlake.support.dailyFieldsPayload
import com.fitlake.user.domain.UserAccount
import com.fitlake.user.domain.UserId
import com.fitlake.user.application.UserQueryService
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
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionSynchronizationManager
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
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
	DailyAiPersistenceMapper::class,
	JpaDailyDayRepository::class,
	JpaDailyCaptureRepository::class,
	JpaDailyInboxEventRepository::class,
	JpaAiInterpretationLogRepository::class,
	UserPersistenceMapper::class,
	JpaUserAccountRepositoryAdapter::class,
)
class DailyAiPersistenceIntegrationTest @Autowired constructor(
	private val days: JpaDailyDayRepository,
	private val captures: JpaDailyCaptureRepository,
	private val inboxEvents: JpaDailyInboxEventRepository,
	private val logs: JpaAiInterpretationLogRepository,
	private val users: JpaUserAccountRepositoryAdapter,
	private val springCaptures: SpringDataDailyCaptureRepository,
	private val springInboxEvents: SpringDataDailyInboxEventRepository,
	private val jdbcTemplate: JdbcTemplate,
	transactionManager: PlatformTransactionManager,
) {
	private val now = Instant.parse("2026-07-30T10:00:00Z")
	private val date = LocalDate.parse("2026-07-30")
	private val clock = Clock.fixed(now, ZoneId.of("UTC"))
	private val transactions: TransactionExecutor = SpringTransactionExecutor(transactionManager)
	private val captureService = DailyCaptureService(days, captures, transactions, clock)
	private val auditService = DailyAiAuditService(
		days,
		captures,
		inboxEvents,
		logs,
		transactions,
		clock,
	)
	private val terminalService = DailyAiTerminalService(
		days,
		captures,
		inboxEvents,
		logs,
		captureService,
		DailyAiCaptureProposalFactory(noCatalogMatches()),
		transactions,
		clock,
	)
	private val metadata = DailyAiProviderMetadata("OPENAI_COMPATIBLE", "test-model", "daily-capture-v2")
	private var userId: UserId = UserId(UUID.randomUUID())

	@BeforeEach
	fun createUser() {
		userId = UserId(UUID.randomUUID())
		users.save(
			UserAccount(
				userId = userId,
				email = "${userId.value}@example.com",
				displayName = "Daily AI test",
				timezone = ZoneId.of("Europe/Rome"),
				createdAt = now,
				updatedAt = now,
			),
		)
	}

	@Test
	fun `AI capture persists original text audit and backend generated ownership`() {
		val context = prepareMessage("message-a", "A colazione 40 grammi di avena")

		val result = assertIs<DailyAiResult.CaptureCreated>(terminalService.createCapture(context, foodProposal()))
		val event = inboxEvents.findById(context.inboxEventId)!!
		val log = logs.findByInboxEventId(context.inboxEventId)!!

		assertEquals(DailyCaptureStatus.OPEN, result.capture.status)
		assertEquals(userId, result.capture.userId)
		assertEquals(date, result.date)
		assertEquals(context.inboxEventId.value, result.capture.sourceEventId)
		assertEquals(DAILY_CAPTURE_SCHEMA_VERSION, result.capture.payload.schemaVersion)
		val foodItem = result.capture.payload.entries.single().items.single()
		assertEquals(DailyFoodItemSourceType.AI_ESTIMATE, foodItem.sourceType)
		assertEquals("150", foodItem.calculatedNutrition.caloriesKcal?.toPlainString())
		assertEquals(foodItem.itemId.toString(), result.capture.payload.meals.single().items.single().itemTempId)
		assertEquals("A colazione 40 grammi di avena", event.rawText)
		assertEquals(DailyInboxProcessingStatus.PROCESSED, event.processingStatus)
		assertEquals(AiInterpretationStatus.SUCCESS, log.status)
		assertEquals("test-model", log.model)
		assertEquals(result.capture.captureId, log.captureId)
		val nutritionResolutions = log.parsedOutput["nutritionResolutions"] as List<*>
		assertEquals("NO_MATCH", (nutritionResolutions.single() as Map<*, *>)["outcome"])
		assertEquals(
			true,
			jdbcTemplate.queryForObject(
				"SELECT raw_response IS NULL FROM ai_interpretation_log WHERE inbox_event_id = ?",
				Boolean::class.java,
				context.inboxEventId.value,
			),
		)
	}

	@Test
	fun `completed idempotency key replays outcome without duplicate event or capture`() {
		val text = "Messaggio non utilizzabile"
		val context = prepareMessage("same-key", text)
		terminalService.noOp(context, AiNoOpProposal("Nessun dato Daily"))

		val replay = auditService.prepareMessage(
			userId,
			date,
			ZoneId.of("Europe/Rome"),
			"same-key",
			text,
			text,
			metadata,
		)

		assertIs<DailyAiPreparation.Replay>(replay)
		assertIs<DailyAiResult.NoOp>(replay.result)
		assertEquals(1, springInboxEvents.countByUserIdForTest(userId.value))
		assertEquals(0, springCaptures.countByUserIdForTest(userId.value))
	}

	@Test
	fun `successful replay reads the immutable capture snapshot from JSONB`() {
		val text = "A colazione 40 grammi di avena"
		val context = prepareMessage("snapshot-key", text)
		val created = assertIs<DailyAiResult.CaptureCreated>(
			terminalService.createCapture(context, foodProposal()),
		)
		val persisted = requireNotNull(captures.findById(created.capture.captureId))
		captures.save(persisted.accept(now.plusSeconds(1)))

		val replay = assertIs<DailyAiPreparation.Replay>(
			auditService.prepareMessage(
				userId,
				date,
				ZoneId.of("Europe/Rome"),
				"snapshot-key",
				text,
				text,
				metadata,
			),
		)
		val replayed = assertIs<DailyAiResult.CaptureCreated>(replay.result)

		assertEquals(created.capture.captureId, replayed.capture.captureId)
		assertEquals(DailyCaptureStatus.OPEN, replayed.capture.status)
		assertEquals(created.capture.payload, replayed.capture.payload)
		assertEquals(DailyCaptureStatus.ACCEPTED, captures.findById(created.capture.captureId)?.status)
		assertEquals(1, springCaptures.countByUserIdForTest(userId.value))
	}

	@Test
	fun `concurrent same idempotency key invokes AI once outside a database transaction`() {
		val aiEntered = CountDownLatch(1)
		val releaseAi = CountDownLatch(1)
		val calls = AtomicInteger()
		val interpreter = object : DailyAiInterpreter {
			override val metadata = this@DailyAiPersistenceIntegrationTest.metadata

			override fun interpret(
				context: com.fitlake.daily.application.ai.DailyAiRequestContext,
				text: String,
			): DailyAiResult {
				assertFalse(TransactionSynchronizationManager.isActualTransactionActive())
				calls.incrementAndGet()
				aiEntered.countDown()
				check(releaseAi.await(10, TimeUnit.SECONDS)) { "Timed out waiting to finish the fake AI call" }
				return terminalService.createCapture(context, foodProposal())
			}
		}
		val messageService = DailyAiMessageService(
			auditService,
			interpreter,
			UserQueryService(users),
			4000,
		)
		val executor = Executors.newFixedThreadPool(2)
		try {
			val first = executor.submit<Result<DailyAiResult>> {
				runCatching {
					messageService.submitMessage(userId, date, "same-concurrent-key", "avena 40 g")
				}
			}
			assertTrue(aiEntered.await(10, TimeUnit.SECONDS))
			val second = executor.submit<Result<DailyAiResult>> {
				runCatching {
					messageService.submitMessage(userId, date, "same-concurrent-key", "avena 40 g")
				}
			}

			val secondResult = second.get(10, TimeUnit.SECONDS)
			assertIs<DailyAiOperationInProgressException>(secondResult.exceptionOrNull())
			releaseAi.countDown()
			val firstResult = first.get(10, TimeUnit.SECONDS)
			assertIs<DailyAiResult.CaptureCreated>(firstResult.getOrThrow())

			assertEquals(1, calls.get())
			assertEquals(1, springInboxEvents.countByUserIdForTest(userId.value))
			assertEquals(1, springCaptures.countByUserIdForTest(userId.value))
		} finally {
			releaseAi.countDown()
			executor.shutdownNow()
		}
	}

	@Test
	fun `successful reprocess creates a new capture and links then rejects the old proposal`() {
		val old = captureService.createFromUser(userId, date, fieldsPayload())
		val context = prepareReprocess(old.captureId, "reprocess-a", "Peso 79 kg")

		val result = assertIs<DailyAiResult.CaptureCreated>(
			terminalService.createCapture(context, fieldsProposal()),
		)
		val persistedOld = captures.findById(old.captureId)!!
		val event = inboxEvents.findById(context.inboxEventId)!!

		assertNotEquals(old.captureId, result.capture.captureId)
		assertEquals(DailyCaptureStatus.OPEN, result.capture.status)
		assertEquals(DAILY_CAPTURE_SCHEMA_VERSION, result.capture.payload.schemaVersion)
		assertEquals(DailyCaptureType.DAILY_FIELDS, result.capture.payload.type)
		assertEquals("WEIGHT", result.capture.payload.entries.single().type.name)
		assertEquals(BigDecimal("79"), result.capture.payload.entries.single().value)
		assertEquals(DailyCaptureStatus.REJECTED, persistedOld.status)
		assertNotNull(persistedOld.rejectedAt)
		assertEquals(old.captureId, result.replacedCaptureId)
		assertEquals(old.captureId, event.replacesCaptureId)
		assertEquals("Peso 79 kg", event.rawText)
		assertEquals(result.capture.captureId, logs.findByInboxEventId(context.inboxEventId)?.captureId)
	}

	@Test
	fun `failure after new capture persistence rolls back both creation and replacement`() {
		val old = captureService.createFromUser(userId, date, fieldsPayload())
		val context = prepareReprocess(old.captureId, "reprocess-fail", "Peso 79 kg")
		val failingTerminal = DailyAiTerminalService(
			days,
			captures,
			inboxEvents,
			FailAfterSavingLogRepository(logs),
			captureService,
			DailyAiCaptureProposalFactory(noCatalogMatches()),
			transactions,
			clock,
		)

		assertFailsWith<DailyAiPersistenceException> {
			failingTerminal.createCapture(context, fieldsProposal())
		}

		assertEquals(DailyCaptureStatus.OPEN, captures.findById(old.captureId)?.status)
		assertEquals(1, captures.findAllByUserIdAndDayId(userId, old.dayId).size)
		assertEquals(null, logs.findByInboxEventId(context.inboxEventId))
		assertEquals(
			DailyInboxProcessingStatus.PROCESSING,
			inboxEvents.findById(context.inboxEventId)?.processingStatus,
		)
	}

	@Test
	fun `two concurrent reprocess commits produce only one replacement`() {
		val old = captureService.createFromUser(userId, date, fieldsPayload())
		val first = prepareReprocess(old.captureId, "concurrent-a", "Peso 79 kg")
		val second = prepareReprocess(old.captureId, "concurrent-b", "Peso 80 kg")
		val start = CountDownLatch(1)
		val executor = Executors.newFixedThreadPool(2)
		try {
			val futures = listOf(first to fieldsProposal("79"), second to fieldsProposal("80")).map { (context, proposal) ->
				executor.submit<Result<DailyAiResult>> {
					start.await()
					runCatching { terminalService.createCapture(context, proposal) }
				}
			}
			start.countDown()
			val outcomes = futures.map { it.get() }

			assertEquals(1, outcomes.count { it.isSuccess })
			assertEquals(1, outcomes.count { it.isFailure })
			val all = captures.findAllByUserIdAndDayId(userId, old.dayId)
			assertEquals(2, all.size)
			assertEquals(1, all.count { it.status == DailyCaptureStatus.OPEN })
			assertEquals(DailyCaptureStatus.REJECTED, captures.findById(old.captureId)?.status)
		} finally {
			executor.shutdownNow()
		}
	}

	private fun prepareMessage(key: String, text: String): com.fitlake.daily.application.ai.DailyAiRequestContext {
		val preparation = auditService.prepareMessage(
			userId,
			date,
			ZoneId.of("Europe/Rome"),
			key,
			text,
			text,
			metadata,
		)
		return assertIs<DailyAiPreparation.Execute>(preparation).context
	}

	private fun prepareReprocess(
		captureId: com.fitlake.daily.domain.capture.DailyCaptureId,
		key: String,
		text: String,
	): com.fitlake.daily.application.ai.DailyAiRequestContext {
		val preparation = auditService.prepareReprocess(
			userId,
			captureId,
			ZoneId.of("Europe/Rome"),
			key,
			text,
			text,
			metadata,
		)
		return assertIs<DailyAiPreparation.Execute>(preparation).context
	}

	private fun fieldsPayload() = dailyFieldsPayload(bodyWeightKg = BigDecimal("78"))

	private fun foodProposal() = AiCaptureProposal(
		type = "FOOD",
		meals = listOf(
			AiMealProposal(
				mealName = "colazione",
				items = listOf(
					AiFoodItemProposal(
						foodName = "avena",
						quantity = BigDecimal("40"),
						unit = "g",
						calories = BigDecimal("150"),
						proteinG = BigDecimal("5"),
						carbsG = BigDecimal("27"),
						fatG = BigDecimal("3"),
					),
				),
			),
		),
	)

	private fun noCatalogMatches() = DailyAiUserFoodMatchPort { _, _ -> DailyAiUserFoodMatchResult.None }

	private fun fieldsProposal(weight: String = "79") = AiCaptureProposal(
		type = "DAILY_FIELDS",
		fields = com.fitlake.daily.application.ai.AiDailyFieldsProposal(bodyWeightKg = BigDecimal(weight)),
	)

	private class FailAfterSavingLogRepository(
		private val delegate: AiInterpretationLogRepository,
	) : AiInterpretationLogRepository {
		override fun findByInboxEventId(
			inboxEventId: com.fitlake.daily.domain.inbox.DailyInboxEventId,
		): AiInterpretationLog? = delegate.findByInboxEventId(inboxEventId)

		override fun save(log: AiInterpretationLog): AiInterpretationLog {
			delegate.save(log)
			throw DataIntegrityViolationException("forced test failure after log flush")
		}
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

private fun SpringDataDailyInboxEventRepository.countByUserIdForTest(userId: UUID): Long =
	findAll().count { it.userId == userId }.toLong()

private fun SpringDataDailyCaptureRepository.countByUserIdForTest(userId: UUID): Long =
	findAll().count { it.userId == userId }.toLong()
