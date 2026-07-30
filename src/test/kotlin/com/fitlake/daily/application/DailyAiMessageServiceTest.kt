package com.fitlake.daily.application

import com.fitlake.daily.application.ai.AiCaptureProposal
import com.fitlake.daily.application.ai.AiFoodItemProposal
import com.fitlake.daily.application.ai.AiMealProposal
import com.fitlake.daily.application.ai.DailyAiAuditService
import com.fitlake.daily.application.ai.DailyAiIdempotencyConflictException
import com.fitlake.daily.application.ai.DailyAiInvalidOutputException
import com.fitlake.daily.application.ai.DailyAiMessageService
import com.fitlake.daily.application.ai.DailyAiOperationInProgressException
import com.fitlake.daily.application.ai.DailyAiPersistenceException
import com.fitlake.daily.application.ai.DailyAiProviderUnavailableException
import com.fitlake.daily.application.ai.DailyAiProviderMetadata
import com.fitlake.daily.application.ai.DailyAiRequestContext
import com.fitlake.daily.application.ai.DailyAiResult
import com.fitlake.daily.application.ai.DailyAiTerminalService
import com.fitlake.daily.application.capture.DailyCaptureInput
import com.fitlake.daily.application.capture.DailyCaptureService
import com.fitlake.daily.application.capture.DailyFieldsInput
import com.fitlake.daily.application.capture.DailyPayloadFactory
import com.fitlake.daily.domain.ai.AiInterpretationStatus
import com.fitlake.daily.domain.capture.DailyCaptureActor
import com.fitlake.daily.domain.capture.DailyCaptureStatus
import com.fitlake.daily.domain.capture.DailyCaptureType
import com.fitlake.daily.domain.common.DailyDay
import com.fitlake.daily.domain.common.DailyDayStatus
import com.fitlake.daily.domain.inbox.DailyInboxChannel
import com.fitlake.daily.domain.inbox.DailyInboxEvent
import com.fitlake.daily.domain.inbox.DailyInboxProcessingStatus
import com.fitlake.support.DailyAiScript
import com.fitlake.support.ImmediateTransactionExecutor
import com.fitlake.support.InMemoryAiInterpretationLogRepository
import com.fitlake.support.InMemoryDailyCaptureRepository
import com.fitlake.support.InMemoryDailyDayRepository
import com.fitlake.support.InMemoryDailyInboxEventRepository
import com.fitlake.support.InMemoryUserAccountRepository
import com.fitlake.support.ScriptedDailyAiInterpreter
import com.fitlake.user.application.UserQueryService
import com.fitlake.user.domain.UserAccount
import com.fitlake.user.domain.UserId
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.dao.DataAccessResourceFailureException
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DailyAiMessageServiceTest {
	private val now = Instant.parse("2026-07-30T08:00:00Z")
	private val clock = Clock.fixed(now, ZoneId.of("UTC"))
	private val date = LocalDate.parse("2026-07-30")
	private val userId = UserId(UUID.randomUUID())
	private val otherUserId = UserId(UUID.randomUUID())

	private lateinit var days: InMemoryDailyDayRepository
	private lateinit var captures: InMemoryDailyCaptureRepository
	private lateinit var inboxEvents: InMemoryDailyInboxEventRepository
	private lateinit var interpretationLogs: InMemoryAiInterpretationLogRepository
	private lateinit var users: InMemoryUserAccountRepository
	private lateinit var captureService: DailyCaptureService
	private lateinit var auditService: DailyAiAuditService
	private lateinit var terminalService: DailyAiTerminalService
	private lateinit var interpreter: ScriptedDailyAiInterpreter
	private lateinit var messageService: DailyAiMessageService

	@BeforeEach
	fun setUp() {
		days = InMemoryDailyDayRepository()
		captures = InMemoryDailyCaptureRepository()
		inboxEvents = InMemoryDailyInboxEventRepository()
		interpretationLogs = InMemoryAiInterpretationLogRepository()
		users = InMemoryUserAccountRepository().also {
			it.save(account(userId))
			it.save(account(otherUserId))
		}
		captureService = DailyCaptureService(
			dayRepository = days,
			captureRepository = captures,
			payloadFactory = DailyPayloadFactory(),
			transactionExecutor = ImmediateTransactionExecutor,
			clock = clock,
		)
		auditService = DailyAiAuditService(
			dayRepository = days,
			captureRepository = captures,
			inboxEventRepository = inboxEvents,
			interpretationLogRepository = interpretationLogs,
			transactionExecutor = ImmediateTransactionExecutor,
			clock = clock,
		)
		terminalService = DailyAiTerminalService(
			dayRepository = days,
			captureRepository = captures,
			inboxEventRepository = inboxEvents,
			interpretationLogRepository = interpretationLogs,
			captureService = captureService,
			transactionExecutor = ImmediateTransactionExecutor,
			clock = clock,
		)
		interpreter = ScriptedDailyAiInterpreter(terminalService)
		messageService = DailyAiMessageService(
			auditService = auditService,
			interpreter = interpreter,
			userQueryService = UserQueryService(users),
			maxTextLength = 4000,
		)
	}

	@Test
	fun `valid text creates an AI owned open capture for the authenticated user and requested date`() {
		interpreter.script(DailyAiScript.CreateCapture(validFoodProposal()))

		val result = assertIs<DailyAiResult.CaptureCreated>(
			messageService.submitMessage(userId, date, "message-1", "40 grammi di avena a colazione"),
		)

		val capture = result.capture
		assertEquals(date, result.date)
		assertEquals(userId, capture.userId)
		assertEquals(DailyCaptureStatus.OPEN, capture.status)
		assertEquals(DailyCaptureActor.AI, capture.createdBy)
		assertEquals(BigDecimal("0.91"), capture.confidence)
		assertNotNull(capture.sourceEventId)
		assertTrue(capture.payload.meals.single().mealTempId.startsWith("meal_"))
		assertTrue(capture.payload.meals.single().items.single().itemTempId.startsWith("item_"))
		assertEquals(date, days.findById(capture.dayId)?.dayDate)
		assertEquals(1, captures.count())
		assertEquals(1, inboxEvents.count())
		assertEquals(1, interpretationLogs.count())
		assertEquals(AiInterpretationStatus.SUCCESS, interpretationLogs.all().single().status)
	}

	@Test
	fun `same completed message is replayed without another AI call or duplicate capture`() {
		interpreter.script(DailyAiScript.CreateCapture(validFoodProposal()))

		val first = assertIs<DailyAiResult.CaptureCreated>(
			messageService.submitMessage(userId, date, "message-retry", "avena 40 g"),
		)
		val replay = assertIs<DailyAiResult.CaptureCreated>(
			messageService.submitMessage(userId, date, "message-retry", "avena 40 g"),
		)

		assertEquals(first.capture.captureId, replay.capture.captureId)
		assertEquals(1, interpreter.callCount)
		assertEquals(1, captures.count())
		assertEquals(1, inboxEvents.count())
		assertEquals(1, interpretationLogs.count())
	}

	@Test
	fun `idempotent replay returns the original open snapshot after the capture changes state`() {
		interpreter.script(DailyAiScript.CreateCapture(validFoodProposal()))
		val first = assertIs<DailyAiResult.CaptureCreated>(
			messageService.submitMessage(userId, date, "message-snapshot", "avena 40 g"),
		)
		val persisted = requireNotNull(captures.findById(first.capture.captureId))
		captures.save(persisted.accept(now.plusSeconds(1)))

		val replay = assertIs<DailyAiResult.CaptureCreated>(
			messageService.submitMessage(userId, date, "message-snapshot", "avena 40 g"),
		)

		assertEquals(DailyCaptureStatus.OPEN, replay.capture.status)
		assertEquals(first.capture.payload, replay.capture.payload)
		assertEquals(first.capture.version, replay.capture.version)
		assertEquals(DailyCaptureStatus.ACCEPTED, captures.findById(first.capture.captureId)?.status)
		assertEquals(1, interpreter.callCount)
	}

	@Test
	fun `stale processing event renews its lease and can be completed`() {
		val staleAt = now.minusSeconds(6 * 60)
		val day = days.save(DailyDay.open(userId, date, staleAt))
		inboxEvents.save(
			DailyInboxEvent.processing(
				userId = userId,
				dayId = day.dayId,
				channel = DailyInboxChannel.REST_AI_MESSAGE,
				sourceMessageId = "stale-message",
				rawText = "avena 40 g",
				normalizedText = "avena 40 g",
				replacesCaptureId = null,
				at = staleAt,
			),
		)
		interpreter.script(DailyAiScript.CreateCapture(validFoodProposal()))

		val result = assertIs<DailyAiResult.CaptureCreated>(
			messageService.submitMessage(userId, date, "stale-message", "avena 40 g"),
		)

		assertEquals(DailyCaptureStatus.OPEN, result.capture.status)
		assertEquals(DailyInboxProcessingStatus.PROCESSED, inboxEvents.all().single().processingStatus)
		assertEquals(1, interpreter.callCount)
		assertEquals(1, captures.count())
	}

	@Test
	fun `fresh processing lease returns conflict without invoking AI`() {
		val day = days.save(DailyDay.open(userId, date, now))
		inboxEvents.save(
			DailyInboxEvent.processing(
				userId = userId,
				dayId = day.dayId,
				channel = DailyInboxChannel.REST_AI_MESSAGE,
				sourceMessageId = "fresh-message",
				rawText = "avena 40 g",
				normalizedText = "avena 40 g",
				replacesCaptureId = null,
				at = now,
			),
		)
		interpreter.script(DailyAiScript.CreateCapture(validFoodProposal()))

		assertFailsWith<DailyAiOperationInProgressException> {
			messageService.submitMessage(userId, date, "fresh-message", "avena 40 g")
		}

		assertEquals(0, interpreter.callCount)
		assertEquals(0, captures.count())
		assertEquals(DailyInboxProcessingStatus.PROCESSING, inboxEvents.all().single().processingStatus)
	}

	@Test
	fun `expired worker cannot commit after another worker renews the processing lease`() {
		val staleAt = now.minusSeconds(6 * 60)
		val day = days.save(DailyDay.open(userId, date, staleAt))
		val event = inboxEvents.save(
			DailyInboxEvent.processing(
				userId = userId,
				dayId = day.dayId,
				channel = DailyInboxChannel.REST_AI_MESSAGE,
				sourceMessageId = "fenced-message",
				rawText = "avena 40 g",
				normalizedText = "avena 40 g",
				replacesCaptureId = null,
				at = staleAt,
			),
		)
		val metadata = DailyAiProviderMetadata("TEST", "test-model", "daily-capture-v1")
		val staleContext = DailyAiRequestContext(
			inboxEventId = event.inboxEventId,
			userId = userId,
			date = date,
			timezone = ZoneId.of("Europe/Rome"),
			replacesCaptureId = null,
			metadata = metadata,
			startedAt = staleAt,
			processingAttemptId = event.processingAttemptId,
		)
		val renewed = inboxEvents.save(event.renewProcessing(now))

		assertFailsWith<DailyAiOperationInProgressException> {
			terminalService.createCapture(staleContext, validFoodProposal())
		}
		auditService.recordFailure(
			staleContext,
			AiInterpretationStatus.FAILED,
			"AI_PROVIDER_UNAVAILABLE",
			"The AI provider is unavailable",
		)
		assertEquals(0, captures.count())
		assertEquals(0, interpretationLogs.count())
		assertEquals(DailyInboxProcessingStatus.PROCESSING, inboxEvents.findById(event.inboxEventId)?.processingStatus)

		val currentContext = staleContext.copy(
			startedAt = renewed.processingStartedAt,
			processingAttemptId = renewed.processingAttemptId,
		)
		val result = assertIs<DailyAiResult.CaptureCreated>(
			terminalService.createCapture(currentContext, validFoodProposal()),
		)
		assertEquals(DailyCaptureStatus.OPEN, result.capture.status)
		assertEquals(1, captures.count())
	}

	@Test
	fun `same idempotency key cannot be reused with different text`() {
		interpreter.script(DailyAiScript.CreateCapture(validFoodProposal()))
		messageService.submitMessage(userId, date, "message-conflict", "avena 40 g")

		assertFailsWith<DailyAiIdempotencyConflictException> {
			messageService.submitMessage(userId, date, "message-conflict", "banana 1 unit")
		}

		assertEquals(1, interpreter.callCount)
		assertEquals(1, captures.count())
	}

	@Test
	fun `clarification records a terminal outcome without creating a capture`() {
		interpreter.script(DailyAiScript.AskClarification("Quanta avena hai mangiato?"))

		val result = assertIs<DailyAiResult.ClarificationRequired>(
			messageService.submitMessage(userId, date, "clarification-1", "Ho mangiato avena"),
		)

		assertEquals("Quanta avena hai mangiato?", result.question)
		assertEquals(0, captures.count())
		assertEquals(DailyInboxProcessingStatus.PROCESSED, inboxEvents.all().single().processingStatus)
		assertEquals(AiInterpretationStatus.NEEDS_CLARIFICATION, interpretationLogs.all().single().status)
	}

	@Test
	fun `no op ignores the inbox event without creating a capture`() {
		interpreter.script(DailyAiScript.NoOp("Il testo non contiene dati Daily"))

		val result = assertIs<DailyAiResult.NoOp>(
			messageService.submitMessage(userId, date, "noop-1", "Ciao come stai?"),
		)

		assertEquals("Il testo non contiene dati Daily", result.reason)
		assertEquals(0, captures.count())
		assertEquals(DailyInboxProcessingStatus.IGNORED, inboxEvents.all().single().processingStatus)
		assertEquals(AiInterpretationStatus.NO_OP, interpretationLogs.all().single().status)
	}

	@Test
	fun `domain incompatible AI proposal is rejected without a partial capture`() {
		interpreter.script(DailyAiScript.CreateCapture(AiCaptureProposal(type = "FOOD")))

		assertFailsWith<DailyAiInvalidOutputException> {
			messageService.submitMessage(userId, date, "invalid-output-1", "colazione")
		}

		assertEquals(0, captures.count())
		assertEquals(DailyInboxProcessingStatus.FAILED, inboxEvents.all().single().processingStatus)
		assertEquals(AiInterpretationStatus.INVALID_OUTPUT, interpretationLogs.all().single().status)
	}

	@Test
	fun `provider failure leaves no capture and records a sanitized failure`() {
		interpreter.script(DailyAiScript.Fail(DailyAiProviderUnavailableException(IllegalStateException("secret"))))

		assertFailsWith<DailyAiProviderUnavailableException> {
			messageService.submitMessage(userId, date, "provider-error-1", "avena 40 g")
		}

		assertEquals(0, captures.count())
		assertEquals(DailyInboxProcessingStatus.FAILED, inboxEvents.all().single().processingStatus)
		val log = interpretationLogs.all().single()
		assertEquals(AiInterpretationStatus.FAILED, log.status)
		assertEquals("AI_PROVIDER_UNAVAILABLE", log.errorCode)
		assertEquals("The AI provider is unavailable", log.errorMessage)
	}

	@Test
	fun `blank and oversized text fail before creating an event or invoking AI`() {
		assertFailsWith<DailyValidationException> {
			messageService.submitMessage(userId, date, "blank-1", "   ")
		}
		assertFailsWith<DailyValidationException> {
			messageService.submitMessage(userId, date, "long-1", "x".repeat(4001))
		}

		assertEquals(0, interpreter.callCount)
		assertEquals(0, inboxEvents.count())
		assertEquals(0, captures.count())
	}

	@Test
	fun `confirmed day rejects a new message before invoking AI`() {
		val existing = captureService.create(userId, date, manualFieldsInput())
		val confirmed = requireNotNull(days.findById(existing.dayId)).confirm(now.plusSeconds(1))
		days.save(confirmed)
		interpreter.script(DailyAiScript.CreateCapture(validFoodProposal()))

		assertFailsWith<DailyConflictException> {
			messageService.submitMessage(userId, date, "closed-day-1", "avena 40 g")
		}

		assertEquals(0, interpreter.callCount)
		assertEquals(0, inboxEvents.count())
		assertEquals(1, captures.count())
	}

	@Test
	fun `valid reprocess creates a distinct open capture and marks the previous one replaced`() {
		val previous = manualCapture()
		val fullText = "A colazione ho mangiato avena, yogurt e tre biscotti"
		interpreter.script(DailyAiScript.CreateCapture(validFoodProposal("biscotti")))

		val result = assertIs<DailyAiResult.CaptureCreated>(
			messageService.reprocess(userId, previous.captureId, "reprocess-1", fullText),
		)

		assertEquals(previous.captureId, result.replacedCaptureId)
		assertNotEquals(previous.captureId, result.capture.captureId)
		assertEquals(DailyCaptureStatus.OPEN, result.capture.status)
		assertEquals(DailyCaptureActor.AI, result.capture.createdBy)
		val replaced = requireNotNull(captures.findById(previous.captureId))
		assertEquals(DailyCaptureStatus.REJECTED, replaced.status)
		assertEquals(DailyCaptureActor.SYSTEM, replaced.updatedBy)
		assertNotNull(replaced.rejectedAt)
		val event = inboxEvents.all().single()
		assertEquals(DailyInboxChannel.REST_AI_REPROCESS, event.channel)
		assertEquals(fullText, event.rawText)
		assertEquals(previous.captureId, event.replacesCaptureId)
		assertEquals(result.capture.captureId, interpretationLogs.all().single().captureId)
		assertEquals(2, captures.count())
	}

	@Test
	fun `successful reprocess is idempotently replayed`() {
		val previous = manualCapture()
		interpreter.script(DailyAiScript.CreateCapture(validFoodProposal("biscotti")))

		val first = assertIs<DailyAiResult.CaptureCreated>(
			messageService.reprocess(userId, previous.captureId, "reprocess-retry", "testo completo"),
		)
		val replay = assertIs<DailyAiResult.CaptureCreated>(
			messageService.reprocess(userId, previous.captureId, "reprocess-retry", "testo completo"),
		)

		assertEquals(first.capture.captureId, replay.capture.captureId)
		assertEquals(1, interpreter.callCount)
		assertEquals(2, captures.count())
		assertEquals(1, inboxEvents.count())
	}

	@Test
	fun `AI failure during reprocess leaves the previous capture open`() {
		val previous = manualCapture()
		interpreter.script(DailyAiScript.Fail(DailyAiProviderUnavailableException()))

		assertFailsWith<DailyAiProviderUnavailableException> {
			messageService.reprocess(userId, previous.captureId, "reprocess-error", "testo completo")
		}

		assertEquals(DailyCaptureStatus.OPEN, captures.findById(previous.captureId)?.status)
		assertEquals(1, captures.count())
		assertEquals(DailyInboxProcessingStatus.FAILED, inboxEvents.all().single().processingStatus)
	}

	@Test
	fun `clarification during reprocess leaves the previous capture open`() {
		val previous = manualCapture()
		interpreter.script(DailyAiScript.AskClarification("Quanti biscotti?"))

		val result = messageService.reprocess(
			userId,
			previous.captureId,
			"reprocess-clarification",
			"testo completo ambiguo",
		)

		assertIs<DailyAiResult.ClarificationRequired>(result)
		assertEquals(DailyCaptureStatus.OPEN, captures.findById(previous.captureId)?.status)
		assertEquals(1, captures.count())
	}

	@Test
	fun `no op during reprocess leaves the previous capture open`() {
		val previous = manualCapture()
		interpreter.script(DailyAiScript.NoOp("Nessun dato utilizzabile"))

		val result = messageService.reprocess(
			userId,
			previous.captureId,
			"reprocess-noop",
			"testo completo non utilizzabile",
		)

		assertIs<DailyAiResult.NoOp>(result)
		assertEquals(DailyCaptureStatus.OPEN, captures.findById(previous.captureId)?.status)
		assertEquals(1, captures.count())
	}

	@Test
	fun `invalid replacement proposal leaves the previous capture open`() {
		val previous = manualCapture()
		interpreter.script(DailyAiScript.CreateCapture(AiCaptureProposal(type = "UNSUPPORTED")))

		assertFailsWith<DailyAiInvalidOutputException> {
			messageService.reprocess(userId, previous.captureId, "reprocess-invalid", "testo completo")
		}

		assertEquals(DailyCaptureStatus.OPEN, captures.findById(previous.captureId)?.status)
		assertEquals(1, captures.count())
	}

	@Test
	fun `failure while saving the replacement leaves the previous capture open`() {
		val previous = manualCapture()
		interpreter.script(DailyAiScript.CreateCapture(validFoodProposal("biscotti")))
		captures.failureOnNextSave = DataAccessResourceFailureException("simulated write failure")

		assertFailsWith<DailyAiPersistenceException> {
			messageService.reprocess(userId, previous.captureId, "reprocess-write-failure", "testo completo")
		}

		assertEquals(DailyCaptureStatus.OPEN, captures.findById(previous.captureId)?.status)
		assertNull(captures.all().single().sourceEventId)
		assertEquals(1, captures.count())
		assertEquals(DailyInboxProcessingStatus.FAILED, inboxEvents.all().single().processingStatus)
	}

	@Test
	fun `accepted capture cannot be reprocessed`() {
		val previous = manualCapture()
		captures.save(previous.accept(now.plusSeconds(1)))

		assertFailsWith<DailyConflictException> {
			messageService.reprocess(userId, previous.captureId, "accepted-reprocess", "testo completo")
		}

		assertEquals(0, interpreter.callCount)
		assertEquals(0, inboxEvents.count())
	}

	@Test
	fun `rejected capture cannot be reprocessed`() {
		val previous = manualCapture()
		captures.save(previous.reject(now.plusSeconds(1)))

		assertFailsWith<DailyConflictException> {
			messageService.reprocess(userId, previous.captureId, "rejected-reprocess", "testo completo")
		}

		assertEquals(0, interpreter.callCount)
		assertEquals(0, inboxEvents.count())
	}

	@Test
	fun `soft deleted capture cannot be reprocessed`() {
		val previous = manualCapture()
		captures.save(previous.softDelete(now.plusSeconds(1)))

		assertFailsWith<DailyConflictException> {
			messageService.reprocess(userId, previous.captureId, "deleted-reprocess", "testo completo")
		}

		assertEquals(0, interpreter.callCount)
	}

	@Test
	fun `capture owned by another user is hidden during reprocess`() {
		val previous = manualCapture()

		assertFailsWith<DailyNotFoundException> {
			messageService.reprocess(otherUserId, previous.captureId, "foreign-reprocess", "testo completo")
		}

		assertEquals(0, interpreter.callCount)
		assertEquals(0, inboxEvents.count())
	}

	@Test
	fun `capture on a confirmed day cannot be reprocessed`() {
		val previous = manualCapture()
		val day = requireNotNull(days.findById(previous.dayId))
		days.save(day.confirm(now.plusSeconds(1)))

		assertFailsWith<DailyConflictException> {
			messageService.reprocess(userId, previous.captureId, "closed-reprocess", "testo completo")
		}

		assertEquals(DailyDayStatus.CONFIRMED, days.findById(previous.dayId)?.status)
		assertEquals(DailyCaptureStatus.OPEN, captures.findById(previous.captureId)?.status)
		assertEquals(0, interpreter.callCount)
		assertEquals(0, inboxEvents.count())
	}

	private fun validFoodProposal(foodName: String = "avena") = AiCaptureProposal(
		type = "FOOD",
		meals = listOf(
			AiMealProposal(
				mealName = "colazione",
				items = listOf(
					AiFoodItemProposal(
						foodName = foodName,
						quantity = BigDecimal("40"),
						unit = "grammi",
					),
				),
			),
		),
		confidence = BigDecimal("0.91"),
	)

	private fun manualCapture() = captureService.create(userId, date, manualFieldsInput())

	private fun manualFieldsInput() = DailyCaptureInput(
		type = DailyCaptureType.DAILY_FIELDS,
		fields = DailyFieldsInput(sleepHours = BigDecimal("7")),
	)

	private fun account(id: UserId) = UserAccount(
		userId = id,
		email = null,
		displayName = null,
		timezone = ZoneId.of("Europe/Rome"),
		createdAt = now,
		updatedAt = now,
	)
}
