package com.fitlake.daily.application.ai

import com.fitlake.daily.application.DailyConflictException
import com.fitlake.daily.application.DailyNotFoundException
import com.fitlake.daily.application.DailyStateCorruptionException
import com.fitlake.daily.application.DailyValidationException
import com.fitlake.daily.application.capture.DailyCaptureService
import com.fitlake.daily.application.port.AiInterpretationLogRepository
import com.fitlake.daily.application.port.DailyCaptureRepository
import com.fitlake.daily.application.port.DailyCaptureAuditRepository
import com.fitlake.daily.application.port.DailyDayRepository
import com.fitlake.daily.application.port.DailyInboxEventRepository
import com.fitlake.daily.domain.ai.AiInterpretationLog
import com.fitlake.daily.domain.ai.AiInterpretationLogId
import com.fitlake.daily.domain.ai.AiInterpretationStatus
import com.fitlake.daily.domain.audit.DailyCaptureAudit
import com.fitlake.daily.domain.capture.DailyCapture
import com.fitlake.daily.domain.capture.DailyCaptureEntryType
import com.fitlake.daily.domain.capture.DailyCaptureStatus
import com.fitlake.daily.domain.capture.DailyFoodItemSourceType
import com.fitlake.daily.domain.common.DailyDayStatus
import com.fitlake.daily.domain.inbox.DailyInboxEvent
import com.fitlake.daily.domain.inbox.DailyInboxProcessingStatus
import com.fitlake.shared.application.TransactionExecutor
import com.fitlake.shared.application.elapsedMilliseconds
import org.slf4j.LoggerFactory
import org.springframework.dao.DataAccessException
import org.springframework.dao.OptimisticLockingFailureException
import org.springframework.stereotype.Service
import java.time.Clock
import java.util.UUID

/**
 * Backend-owned terminal coordinator. The model never receives this service and
 * cannot invoke persistence: it only returns a pure [DailyMessageInterpretation].
 */
@Service
class DailyAiTerminalService(
	private val dayRepository: DailyDayRepository,
	private val captureRepository: DailyCaptureRepository,
	private val inboxEventRepository: DailyInboxEventRepository,
	private val interpretationLogRepository: AiInterpretationLogRepository,
	private val captureAuditRepository: DailyCaptureAuditRepository,
	private val captureService: DailyCaptureService,
	private val proposalFactory: DailyAiCaptureProposalFactory,
	private val transactionExecutor: TransactionExecutor,
	private val clock: Clock,
) {
	fun complete(
		context: DailyAiRequestContext,
		rawText: String,
		interpreted: InterpretedDailyMessage,
	): DailyAiResult {
		val interpretation = interpreted.interpretation
		return if (interpretation.outcome == DailyMessageInterpretationOutcome.NO_RELEVANT_DATA) {
			completeWithoutCapture(context, interpreted)
		} else {
			val resolutionStartedAtNanos = System.nanoTime()
			val resolvedCapture = proposalFactory.create(context.userId, rawText, interpretation)
			val resolutionDurationMs = elapsedMilliseconds(resolutionStartedAtNanos)
			persistCapture(context, interpreted, resolvedCapture, resolutionDurationMs)
		}
	}

	private fun persistCapture(
		context: DailyAiRequestContext,
		interpreted: InterpretedDailyMessage,
		resolvedCapture: DailyAiCaptureBuildResult,
		resolutionDurationMs: Long,
	): DailyAiResult {
		val startedAtNanos = System.nanoTime()
		val persistence = persistSafely {
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
				if (capture != null) validateReplacement(context, event, capture)
			}
			if (captureRepository.findBySourceEventId(event.inboxEventId.value) != null) {
				throw DailyStateCorruptionException("Daily AI event already owns a capture")
			}

			val created = captureService.createFromAi(
				userId = context.userId,
				date = context.date,
				payload = resolvedCapture.payload,
				sourceEventId = event.inboxEventId.value,
				confidence = interpreted.interpretation.confidence,
			)
			val captureResult = created.toAiCaptureResult()
			captureAuditRepository.save(
				DailyCaptureAudit.create(
					captureId = created.captureId,
					userId = created.userId,
					newPayload = created.payload,
					actor = created.createdBy,
					requestId = null,
					at = created.createdAt,
				),
			)
			val replacement = previous?.let { oldCapture ->
				val replacedAt = clock.instant()
				val replaced = captureRepository.save(oldCapture.replaceByReprocess(replacedAt))
				captureAuditRepository.save(
					DailyCaptureAudit.replacedByReprocess(
						captureId = oldCapture.captureId,
						userId = oldCapture.userId,
						relatedCaptureId = created.captureId,
						oldVersion = oldCapture.version,
						newVersion = replaced.version,
						requestId = null,
						at = replacedAt,
					),
				)
				ReprocessedCapture(oldCapture, replaced)
			}
			val now = clock.instant()
			val operationOutcome = if (previous == null) "CAPTURE_CREATED" else "CAPTURE_REPLACED"
			val catalogMatchCount = resolvedCapture.nutritionResolutions.count {
				it.outcome == DailyAiNutritionResolutionOutcome.CATALOG_MATCH
			}
			val aiFallbackCount = resolvedCapture.nutritionResolutions.size - catalogMatchCount
			interpretationLogRepository.save(
				newLog(
					context = context,
					status = AiInterpretationStatus.SUCCESS,
					capture = created,
					interpreted = interpreted,
					parsedOutput = captureResult.toSafeAuditOutput(
						outcome = operationOutcome,
						interpretationOutcome = resolvedCapture.interpretationOutcome,
						nutritionResolutions = resolvedCapture.nutritionResolutions,
					),
					at = now,
				),
			)
			inboxEventRepository.save(event.processed(now))
			PersistedAiCapture(
				result = DailyAiResult.CaptureCreated(
					context.date,
					captureResult,
					previous?.captureId,
					resolvedCapture.interpretationOutcome,
				),
				created = created,
				replacement = replacement,
				catalogMatchCount = catalogMatchCount,
				aiFallbackCount = aiFallbackCount,
			)
		}
	}
		logCapturePersistence(context, persistence, startedAtNanos, resolutionDurationMs)
		return persistence.result
	}

	private fun completeWithoutCapture(
		context: DailyAiRequestContext,
		interpreted: InterpretedDailyMessage,
	): DailyAiResult = persistSafely {
		transactionExecutor.required {
			val event = requireProcessingEvent(context)
			val now = clock.instant()
			val result = DailyAiResult.NoRelevantData(context.date)
			interpretationLogRepository.save(
				newLog(
					context = context,
					status = AiInterpretationStatus.NO_RELEVANT_DATA,
					capture = null,
					interpreted = interpreted,
					parsedOutput = linkedMapOf(
						"outcome" to "NO_RELEVANT_DATA",
						"interpretationOutcome" to DailyMessageInterpretationOutcome.NO_RELEVANT_DATA.name,
						"retryCount" to interpreted.retryCount,
						"entryCount" to 0,
						"foodItemCount" to 0,
						"unresolvedFragmentCount" to 0,
					),
					at = now,
				),
			)
			inboxEventRepository.save(event.ignored(now))
			result
		}
	}

	private fun logCapturePersistence(
		context: DailyAiRequestContext,
		persistence: PersistedAiCapture,
		startedAtNanos: Long,
		resolutionDurationMs: Long,
	) {
		val created = persistence.created
		val foodItemCount = created.payload.entries.sumOf { it.items.size }
		val replacement = persistence.replacement
		if (replacement == null) {
			logger.atInfo()
				.addKeyValue("event", "daily_capture_created")
				.addKeyValue("outcome", "success")
				.addKeyValue("userRef", context.userId.value)
				.addKeyValue("captureId", created.captureId.value)
				.addKeyValue("captureType", created.captureType)
				.addKeyValue("captureStatus", created.status)
				.addKeyValue("sourceType", created.createdBy)
				.addKeyValue("entryCount", created.payload.entries.size)
				.addKeyValue("foodItemCount", foodItemCount)
				.addKeyValue("newVersion", created.version)
				.addKeyValue("durationMs", elapsedMilliseconds(startedAtNanos))
				.log("Daily capture created")
		} else {
			logger.atInfo()
				.addKeyValue("event", "daily_capture_reprocessed")
				.addKeyValue("outcome", "success")
				.addKeyValue("userRef", context.userId.value)
				.addKeyValue("captureId", replacement.before.captureId.value)
				.addKeyValue("replacementCaptureId", created.captureId.value)
				.addKeyValue("captureType", created.captureType)
				.addKeyValue("oldStatus", replacement.before.status)
				.addKeyValue("newStatus", replacement.after.status)
				.addKeyValue("oldVersion", replacement.before.version)
				.addKeyValue("newVersion", replacement.after.version)
				.addKeyValue("entryCount", created.payload.entries.size)
				.addKeyValue("foodItemCount", foodItemCount)
				.addKeyValue("durationMs", elapsedMilliseconds(startedAtNanos))
				.log("Daily capture reprocessed")
		}

		if (persistence.catalogMatchCount + persistence.aiFallbackCount > 0) {
			logger.atDebug()
				.addKeyValue("event", "daily_user_food_resolution_completed")
				.addKeyValue("outcome", "success")
				.addKeyValue("userRef", context.userId.value)
				.addKeyValue("inboxEventId", context.inboxEventId.value)
				.addKeyValue("provider", context.metadata.provider)
				.addKeyValue("model", context.metadata.model)
				.addKeyValue("personalFoodMatchCount", persistence.catalogMatchCount)
				.addKeyValue("aiFallbackCount", persistence.aiFallbackCount)
				.addKeyValue("durationMs", resolutionDurationMs)
				.log("Daily user food resolution completed")
		}
	}

	private fun requireProcessingEvent(context: DailyAiRequestContext): DailyInboxEvent {
		val event = inboxEventRepository.findByIdForUpdate(context.inboxEventId)
			?: throw DailyStateCorruptionException("Daily AI inbox event disappeared")
		if (event.processingAttemptId != context.processingAttemptId) throw DailyAiOperationInProgressException()
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

	private fun newLog(
		context: DailyAiRequestContext,
		status: AiInterpretationStatus,
		capture: DailyCapture?,
		interpreted: InterpretedDailyMessage,
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
		inputText = null,
		contextSnapshot = context.snapshot() + linkedMapOf(
			"semanticOutcome" to interpreted.interpretation.outcome.name,
			"retryCount" to interpreted.retryCount,
			"inputTokens" to interpreted.inputTokens,
			"outputTokens" to interpreted.outputTokens,
		),
		parsedOutput = parsedOutput,
		status = status,
		confidence = interpreted.interpretation.confidence,
		errorCode = null,
		errorMessage = null,
		latencyMs = context.latencyMs(at),
		createdAt = at,
	)

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

	private data class PersistedAiCapture(
		val result: DailyAiResult.CaptureCreated,
		val created: DailyCapture,
		val replacement: ReprocessedCapture?,
		val catalogMatchCount: Int,
		val aiFallbackCount: Int,
	)

	private data class ReprocessedCapture(
		val before: DailyCapture,
		val after: DailyCapture,
	)

	private companion object {
		val logger = LoggerFactory.getLogger(DailyAiTerminalService::class.java)
	}
}
