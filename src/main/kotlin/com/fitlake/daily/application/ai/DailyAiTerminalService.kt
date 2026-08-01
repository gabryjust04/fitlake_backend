package com.fitlake.daily.application.ai

import com.fitlake.daily.application.DailyConflictException
import com.fitlake.daily.application.DailyNotFoundException
import com.fitlake.daily.application.DailyStateCorruptionException
import com.fitlake.daily.application.DailyValidationException
import com.fitlake.daily.application.capture.DailyCaptureService
import com.fitlake.daily.application.port.AiInterpretationLogRepository
import com.fitlake.daily.application.port.DailyCaptureRepository
import com.fitlake.daily.application.port.DailyDayRepository
import com.fitlake.daily.application.port.DailyInboxEventRepository
import com.fitlake.daily.domain.ai.AiInterpretationLog
import com.fitlake.daily.domain.ai.AiInterpretationLogId
import com.fitlake.daily.domain.ai.AiInterpretationStatus
import com.fitlake.daily.domain.capture.DailyCapture
import com.fitlake.daily.domain.capture.DailyCaptureStatus
import com.fitlake.daily.domain.common.DailyDayStatus
import com.fitlake.daily.domain.inbox.DailyInboxEvent
import com.fitlake.daily.domain.inbox.DailyInboxProcessingStatus
import com.fitlake.shared.application.TransactionExecutor
import org.springframework.dao.DataAccessException
import org.springframework.dao.OptimisticLockingFailureException
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.time.Clock
import java.util.UUID

@Service
class DailyAiTerminalService(
	private val dayRepository: DailyDayRepository,
	private val captureRepository: DailyCaptureRepository,
	private val inboxEventRepository: DailyInboxEventRepository,
	private val interpretationLogRepository: AiInterpretationLogRepository,
	private val captureService: DailyCaptureService,
	private val proposalFactory: DailyAiCaptureProposalFactory,
	private val transactionExecutor: TransactionExecutor,
	private val clock: Clock,
) {
	fun createCapture(context: DailyAiRequestContext, proposal: AiCaptureProposal): DailyAiResult = persistSafely {
		validateConfidence(proposal.confidence)
		val resolvedCapture = proposalFactory.create(context.userId, proposal)
		transactionExecutor.required {
				val event = requireProcessingEvent(context)
				val day = dayRepository.findByIdForUpdate(event.dayId)
					?: throw DailyStateCorruptionException("Daily AI event references a missing day")
				if (day.userId != context.userId || day.dayDate != context.date) {
					throw DailyStateCorruptionException("Daily AI request context does not match its day")
				}
				if (day.status == DailyDayStatus.CONFIRMED) {
					throw DailyConflictException("Confirmed day cannot receive AI captures")
				}

				val previous = event.replacesCaptureId?.let { captureId ->
					captureRepository.findByIdForUpdate(captureId)
						?: throw DailyNotFoundException.capture(captureId.value)
				}.also { capture ->
					if (capture != null) {
						validateReplacement(context, event, capture)
					}
				}

				if (captureRepository.findBySourceEventId(event.inboxEventId.value) != null) {
					throw DailyStateCorruptionException("Daily AI event already owns a capture")
				}

				val created = captureService.createFromAi(
					userId = context.userId,
					date = context.date,
					payload = resolvedCapture.payload,
					sourceEventId = event.inboxEventId.value,
					confidence = proposal.confidence,
				)
				val captureResult = created.toAiCaptureResult()
				previous?.let { captureRepository.save(it.replaceByReprocess(clock.instant())) }
				val now = clock.instant()
				interpretationLogRepository.save(
					newLog(
						context = context,
						event = event,
						status = AiInterpretationStatus.SUCCESS,
						capture = created,
						confidence = proposal.confidence,
						parsedOutput = captureResult.toAuditOutput(
							outcome = if (previous == null) "CAPTURE_CREATED" else "CAPTURE_REPLACED",
							nutritionResolutions = resolvedCapture.nutritionResolutions,
						),
						at = now,
					),
				)
				inboxEventRepository.save(event.processed(now))
				DailyAiResult.CaptureCreated(context.date, captureResult, previous?.captureId)
		}
	}

	fun askClarification(
		context: DailyAiRequestContext,
		proposal: AiClarificationProposal,
	): DailyAiResult {
		val question = proposal.question.normalizedRequired("Clarification question", 500)
		return completeWithoutCapture(
			context = context,
			status = AiInterpretationStatus.NEEDS_CLARIFICATION,
			parsedOutput = mapOf("outcome" to "CLARIFICATION_REQUIRED", "question" to question),
			ignored = false,
			result = DailyAiResult.ClarificationRequired(context.date, question),
		)
	}

	fun noOp(context: DailyAiRequestContext, proposal: AiNoOpProposal): DailyAiResult {
		val reason = proposal.reason.normalizedRequired("No-op reason", 500)
		return completeWithoutCapture(
			context = context,
			status = AiInterpretationStatus.NO_OP,
			parsedOutput = mapOf("outcome" to "NO_OP", "reason" to reason),
			ignored = true,
			result = DailyAiResult.NoOp(context.date, reason),
		)
	}

	private fun completeWithoutCapture(
		context: DailyAiRequestContext,
		status: AiInterpretationStatus,
		parsedOutput: Map<String, Any?>,
		ignored: Boolean,
		result: DailyAiResult,
	): DailyAiResult = persistSafely {
		transactionExecutor.required {
			val event = requireProcessingEvent(context)
			val now = clock.instant()
			interpretationLogRepository.save(
				newLog(
					context = context,
					event = event,
					status = status,
					capture = null,
					confidence = null,
					parsedOutput = parsedOutput,
					at = now,
				),
			)
			inboxEventRepository.save(if (ignored) event.ignored(now) else event.processed(now))
			result
		}
	}

	private fun requireProcessingEvent(context: DailyAiRequestContext): DailyInboxEvent {
		val event = inboxEventRepository.findByIdForUpdate(context.inboxEventId)
			?: throw DailyStateCorruptionException("Daily AI inbox event disappeared")
		if (event.processingAttemptId != context.processingAttemptId) {
			throw DailyAiOperationInProgressException()
		}
		if (
			event.userId != context.userId ||
			event.replacesCaptureId != context.replacesCaptureId ||
			event.processingStatus != DailyInboxProcessingStatus.PROCESSING
		) {
			throw DailyStateCorruptionException("Daily AI inbox event is not executable")
		}
		if (interpretationLogRepository.findByInboxEventId(event.inboxEventId) != null) {
			throw DailyStateCorruptionException("Daily AI inbox event already has a terminal result")
		}
		return event
	}

	private fun validateReplacement(
		context: DailyAiRequestContext,
		event: DailyInboxEvent,
		capture: DailyCapture,
	) {
		if (
			capture.userId != context.userId ||
			capture.dayId != event.dayId ||
			capture.captureId != context.replacesCaptureId
		) {
			throw DailyNotFoundException.capture(context.replacesCaptureId!!.value)
		}
		if (capture.status != DailyCaptureStatus.OPEN) {
			throw DailyConflictException("Only an open capture can be reprocessed")
		}
	}

	private fun validateConfidence(confidence: BigDecimal?) {
		if (confidence != null && confidence !in BigDecimal.ZERO..BigDecimal.ONE) {
			throw DailyAiInvalidOutputException(IllegalArgumentException("Confidence is outside the allowed range"))
		}
	}

	private fun newLog(
		context: DailyAiRequestContext,
		event: DailyInboxEvent,
		status: AiInterpretationStatus,
		capture: DailyCapture?,
		confidence: BigDecimal?,
		parsedOutput: Map<String, Any?>,
		at: java.time.Instant,
	): AiInterpretationLog = AiInterpretationLog(
		aiLogId = AiInterpretationLogId(UUID.randomUUID()),
		userId = context.userId,
		inboxEventId = context.inboxEventId,
		captureId = capture?.captureId,
		provider = context.metadata.provider,
		model = context.metadata.model,
		promptVersion = context.metadata.promptVersion,
		inputText = event.rawText,
		contextSnapshot = context.snapshot(),
		parsedOutput = parsedOutput,
		status = status,
		confidence = confidence,
		errorCode = null,
		errorMessage = null,
		latencyMs = context.latencyMs(at),
		createdAt = at,
	)

	private fun String?.required(label: String): String =
		this?.trim()?.takeIf(String::isNotEmpty) ?: throw IllegalArgumentException("$label is required")

	private fun String?.normalizedRequired(label: String, maxLength: Int): String {
		val value = try {
			required(label)
		} catch (exception: IllegalArgumentException) {
			throw DailyAiInvalidOutputException(exception)
		}
		if (value.length > maxLength) {
			throw DailyAiInvalidOutputException(IllegalArgumentException("$label is too long"))
		}
		return value
	}

	private fun <T : Any> persistSafely(action: () -> T): T = try {
		action()
	} catch (exception: DailyAiException) {
		throw exception
	} catch (exception: DailyConflictException) {
		throw exception
	} catch (exception: DailyNotFoundException) {
		throw exception
	} catch (exception: DailyStateCorruptionException) {
		throw exception
	} catch (exception: DailyValidationException) {
		throw DailyAiInvalidOutputException(exception)
	} catch (exception: OptimisticLockingFailureException) {
		throw DailyConflictException("Daily state changed concurrently")
	} catch (exception: DataAccessException) {
		throw DailyAiPersistenceException(exception)
	}
}
