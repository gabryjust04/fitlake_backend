package com.fitlake.daily.application.ai

import com.fitlake.daily.application.DailyConcurrentCreationException
import com.fitlake.daily.application.DailyConflictException
import com.fitlake.daily.application.DailyNotFoundException
import com.fitlake.daily.application.DailyStateCorruptionException
import com.fitlake.daily.application.port.AiInterpretationLogRepository
import com.fitlake.daily.application.port.DailyCaptureRepository
import com.fitlake.daily.application.port.DailyDayRepository
import com.fitlake.daily.application.port.DailyInboxEventRepository
import com.fitlake.daily.domain.ai.AiInterpretationLog
import com.fitlake.daily.domain.ai.AiInterpretationLogId
import com.fitlake.daily.domain.ai.AiInterpretationStatus
import com.fitlake.daily.domain.capture.DailyCaptureId
import com.fitlake.daily.domain.capture.DailyCaptureStatus
import com.fitlake.daily.domain.common.DailyDay
import com.fitlake.daily.domain.common.DailyDayStatus
import com.fitlake.daily.domain.inbox.DailyInboxChannel
import com.fitlake.daily.domain.inbox.DailyInboxEvent
import com.fitlake.daily.domain.inbox.DailyInboxProcessingStatus
import com.fitlake.shared.application.TransactionExecutor
import com.fitlake.user.domain.UserId
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

@Service
class DailyAiAuditService(
	private val dayRepository: DailyDayRepository,
	private val captureRepository: DailyCaptureRepository,
	private val inboxEventRepository: DailyInboxEventRepository,
	private val interpretationLogRepository: AiInterpretationLogRepository,
	private val transactionExecutor: TransactionExecutor,
	private val clock: Clock,
) {
	fun prepareMessage(
		userId: UserId,
		date: LocalDate,
		timezone: ZoneId,
		idempotencyKey: String,
		rawText: String,
		normalizedText: String,
		metadata: DailyAiProviderMetadata,
	): DailyAiPreparation = retryConcurrentCreation {
		transactionExecutor.required {
			prepareMessageOnce(
				userId,
				date,
				timezone,
				idempotencyKey,
				rawText,
				normalizedText,
				metadata,
			)
		}
	}

	fun prepareReprocess(
		userId: UserId,
		captureId: DailyCaptureId,
		timezone: ZoneId,
		idempotencyKey: String,
		rawText: String,
		normalizedText: String,
		metadata: DailyAiProviderMetadata,
	): DailyAiPreparation = retryConcurrentCreation {
		transactionExecutor.required {
			val existing = existingEvent(userId, DailyInboxChannel.REST_AI_REPROCESS, idempotencyKey)
			if (existing != null) {
				validateExisting(existing, normalizedText, captureId, null)
				return@required resumeOrReplay(existing, timezone, metadata) { event ->
					val day = dayRepository.findByIdForUpdate(event.dayId)
						?: throw DailyNotFoundException("Capture day was not found")
					val capture = captureRepository.findByIdForUpdate(captureId)
						?: throw DailyNotFoundException.capture(captureId.value)
					validateReprocessable(userId, day, capture.captureId, capture.userId, capture.status)
					day
				}
			}

			val candidate = captureRepository.findById(captureId)
				?: throw DailyNotFoundException.capture(captureId.value)
			if (candidate.userId != userId) {
				throw DailyNotFoundException.capture(captureId.value)
			}
			val day = dayRepository.findByIdForUpdate(candidate.dayId)
				?: throw DailyNotFoundException("Capture day was not found")
			val capture = captureRepository.findByIdForUpdate(captureId)
				?: throw DailyNotFoundException.capture(captureId.value)
			validateReprocessable(userId, day, capture.captureId, capture.userId, capture.status)

			val now = clock.instant()
			val event = inboxEventRepository.save(
				DailyInboxEvent.processing(
					userId = userId,
					dayId = day.dayId,
					channel = DailyInboxChannel.REST_AI_REPROCESS,
					sourceMessageId = idempotencyKey,
					rawText = rawText,
					normalizedText = normalizedText,
					replacesCaptureId = captureId,
					at = now,
				),
			)
			DailyAiPreparation.Execute(event.toContext(day.dayDate, timezone, metadata))
		}
	}

	fun recordFailure(
		context: DailyAiRequestContext,
		status: AiInterpretationStatus,
		errorCode: String,
		errorMessage: String,
	) {
		try {
			transactionExecutor.required {
				val event = inboxEventRepository.findByIdForUpdate(context.inboxEventId)
					?: throw DailyStateCorruptionException("Daily AI inbox event disappeared")
				if (
					event.processingStatus != DailyInboxProcessingStatus.PROCESSING ||
					event.processingAttemptId != context.processingAttemptId
				) {
					return@required event
				}
				val now = clock.instant()
				if (interpretationLogRepository.findByInboxEventId(event.inboxEventId) == null) {
					interpretationLogRepository.save(
						newLog(
							context = context,
							status = status,
							parsedOutput = emptyMap(),
							errorCode = errorCode,
							errorMessage = errorMessage,
							at = now,
						),
					)
				}
				inboxEventRepository.save(event.failed(errorCode, errorMessage, now))
			}
		} catch (_: RuntimeException) {
			// Preserve the original sanitized failure when even audit persistence is unavailable.
		}
	}

	private fun prepareMessageOnce(
		userId: UserId,
		date: LocalDate,
		timezone: ZoneId,
		idempotencyKey: String,
		rawText: String,
		normalizedText: String,
		metadata: DailyAiProviderMetadata,
	): DailyAiPreparation {
		val existing = existingEvent(userId, DailyInboxChannel.REST_AI_MESSAGE, idempotencyKey)
		if (existing != null) {
			validateExisting(existing, normalizedText, null, date)
			return resumeOrReplay(existing, timezone, metadata) { event ->
				val day = dayRepository.findByIdForUpdate(event.dayId)
					?: throw DailyStateCorruptionException("Daily AI inbox event references a missing day")
				if (day.userId != userId || day.dayDate != date) {
					throw DailyStateCorruptionException("Daily AI inbox event references the wrong day")
				}
				ensureEditable(day)
				day
			}
		}

		val now = clock.instant()
		val day = dayRepository.findByUserIdAndDateForUpdate(userId, date)
			?: dayRepository.save(DailyDay.open(userId, date, now))
		ensureEditable(day)
		val event = inboxEventRepository.save(
			DailyInboxEvent.processing(
				userId = userId,
				dayId = day.dayId,
				channel = DailyInboxChannel.REST_AI_MESSAGE,
				sourceMessageId = idempotencyKey,
				rawText = rawText,
				normalizedText = normalizedText,
				replacesCaptureId = null,
				at = now,
			),
		)
		return DailyAiPreparation.Execute(event.toContext(date, timezone, metadata))
	}

	private fun existingEvent(
		userId: UserId,
		channel: DailyInboxChannel,
		idempotencyKey: String,
	): DailyInboxEvent? = inboxEventRepository.findByUserIdAndChannelAndSourceMessageId(
		userId,
		channel,
		idempotencyKey,
	)

	private fun validateExisting(
		event: DailyInboxEvent,
		normalizedText: String,
		replacesCaptureId: DailyCaptureId?,
		expectedDate: LocalDate?,
	) {
		val day = dayRepository.findById(event.dayId)
			?: throw DailyStateCorruptionException("Daily AI inbox event references a missing day")
		if (
			event.normalizedText != normalizedText ||
			event.replacesCaptureId != replacesCaptureId ||
			(expectedDate != null && day.dayDate != expectedDate)
		) {
			throw DailyAiIdempotencyConflictException()
		}
	}

	private fun resumeOrReplay(
		existing: DailyInboxEvent,
		timezone: ZoneId,
		metadata: DailyAiProviderMetadata,
		validateResume: (DailyInboxEvent) -> DailyDay,
	): DailyAiPreparation {
		val event = inboxEventRepository.findByIdForUpdate(existing.inboxEventId)
			?: throw DailyStateCorruptionException("Daily AI inbox event disappeared")
		if (
			event.processingStatus == DailyInboxProcessingStatus.RECEIVED ||
			event.processingStatus == DailyInboxProcessingStatus.PROCESSING
		) {
			val now = clock.instant()
			if (now.isBefore(event.processingStartedAt.plus(PROCESSING_LEASE))) {
				throw DailyAiOperationInProgressException()
			}
			val day = validateResume(event)
			val renewed = inboxEventRepository.save(event.renewProcessing(now))
			return DailyAiPreparation.Execute(renewed.toContext(day.dayDate, timezone, metadata))
		}
		return replayTerminal(event)
	}

	private fun replayTerminal(event: DailyInboxEvent): DailyAiPreparation {
		val log = interpretationLogRepository.findByInboxEventId(event.inboxEventId)
			?: throw DailyStateCorruptionException("Terminal Daily AI event has no interpretation log")
		val day = dayRepository.findById(event.dayId)
			?: throw DailyStateCorruptionException("Daily AI event references a missing day")

		return when (log.status) {
			AiInterpretationStatus.SUCCESS -> {
				val captureId = log.captureId
					?: throw DailyStateCorruptionException("Successful AI interpretation has no capture")
				DailyAiPreparation.Replay(
					DailyAiResult.CaptureCreated(
						day.dayDate,
						log.parsedOutput.captureResult(event, captureId, log.confidence),
						event.replacesCaptureId,
					),
				)
			}
			AiInterpretationStatus.NEEDS_CLARIFICATION -> DailyAiPreparation.Replay(
				DailyAiResult.ClarificationRequired(
					day.dayDate,
					log.parsedOutput.requiredString("question"),
				),
			)
			AiInterpretationStatus.NO_OP -> DailyAiPreparation.Replay(
				DailyAiResult.NoOp(day.dayDate, log.parsedOutput.requiredString("reason")),
			)
			AiInterpretationStatus.FAILED,
			AiInterpretationStatus.INVALID_OUTPUT,
			-> throw replayFailure(log.errorCode ?: event.errorCode)
		}
	}

	private fun replayFailure(errorCode: String?): RuntimeException = when (errorCode) {
		"AI_NOT_CONFIGURED" -> DailyAiConfigurationException()
		"AI_TIMEOUT" -> DailyAiTimeoutException()
		"AI_PROVIDER_UNAVAILABLE" -> DailyAiProviderUnavailableException()
		"AI_INVALID_OUTPUT" -> DailyAiInvalidOutputException()
		"AI_PERSISTENCE_ERROR" -> DailyAiPersistenceException()
		"DAILY_CONFLICT" -> DailyConflictException("Daily state changed while the AI request was processed")
		"DAILY_NOT_FOUND" -> DailyNotFoundException("A Daily resource changed while the AI request was processed")
		else -> DailyAiRecordedFailureException(
			errorCode = errorCode ?: "AI_PROCESSING_FAILED",
			safeMessage = "The previous Daily AI request failed",
		)
	}

	private fun validateReprocessable(
		userId: UserId,
		day: DailyDay,
		captureId: DailyCaptureId,
		captureUserId: UserId,
		status: DailyCaptureStatus,
	) {
		if (day.userId != userId || captureUserId != userId) {
			throw DailyNotFoundException.capture(captureId.value)
		}
		ensureEditable(day)
		if (status != DailyCaptureStatus.OPEN) {
			throw DailyConflictException("Only an open capture can be reprocessed")
		}
	}

	private fun ensureEditable(day: DailyDay) {
		if (day.status == DailyDayStatus.CONFIRMED) {
			throw DailyConflictException("Confirmed day cannot receive AI messages")
		}
	}

	private fun DailyInboxEvent.toContext(
		date: LocalDate,
		timezone: ZoneId,
		metadata: DailyAiProviderMetadata,
	): DailyAiRequestContext = DailyAiRequestContext(
		inboxEventId = inboxEventId,
		userId = userId,
		date = date,
		timezone = timezone,
		replacesCaptureId = replacesCaptureId,
		metadata = metadata,
		startedAt = processingStartedAt,
		processingAttemptId = processingAttemptId,
	)

	private fun newLog(
		context: DailyAiRequestContext,
		status: AiInterpretationStatus,
		parsedOutput: Map<String, Any?>,
		errorCode: String?,
		errorMessage: String?,
		at: java.time.Instant,
	): AiInterpretationLog = AiInterpretationLog(
		aiLogId = AiInterpretationLogId(UUID.randomUUID()),
		userId = context.userId,
		inboxEventId = context.inboxEventId,
		captureId = null,
		provider = context.metadata.provider,
		model = context.metadata.model,
		promptVersion = context.metadata.promptVersion,
		inputText = inboxEventRepository.findById(context.inboxEventId)?.rawText
			?: throw DailyStateCorruptionException("Daily AI inbox event disappeared"),
		contextSnapshot = context.snapshot(),
		parsedOutput = parsedOutput,
		status = status,
		confidence = null,
		errorCode = errorCode,
		errorMessage = errorMessage,
		latencyMs = context.latencyMs(at),
		createdAt = at,
	)

	private fun retryConcurrentCreation(action: () -> DailyAiPreparation): DailyAiPreparation = try {
		action()
	} catch (_: DailyConcurrentCreationException) {
		retryOnce(action)
	} catch (_: DailyAiConcurrentRequestException) {
		retryOnce(action)
	}

	private fun retryOnce(action: () -> DailyAiPreparation): DailyAiPreparation = try {
		action()
	} catch (_: DailyConcurrentCreationException) {
		throw DailyAiOperationInProgressException()
	} catch (_: DailyAiConcurrentRequestException) {
		throw DailyAiOperationInProgressException()
	}

	private fun Map<String, Any?>.requiredString(key: String): String =
		(this[key] as? String)?.takeIf(String::isNotBlank)
			?: throw DailyStateCorruptionException("AI audit output is missing $key")

	private companion object {
		val PROCESSING_LEASE: Duration = Duration.ofMinutes(5)
	}
}

internal fun DailyAiRequestContext.snapshot(): Map<String, Any?> = linkedMapOf(
	"date" to date.toString(),
	"timezone" to timezone.id,
	"origin" to "REST",
)

internal fun DailyAiRequestContext.latencyMs(at: java.time.Instant): Int =
	java.time.Duration.between(startedAt, at).toMillis().coerceIn(0, Int.MAX_VALUE.toLong()).toInt()
