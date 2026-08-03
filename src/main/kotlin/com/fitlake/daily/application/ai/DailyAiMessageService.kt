package com.fitlake.daily.application.ai

import com.fitlake.daily.application.DailyConflictException
import com.fitlake.daily.application.DailyNotFoundException
import com.fitlake.daily.application.DailyValidationException
import com.fitlake.daily.domain.ai.AiInterpretationStatus
import com.fitlake.daily.domain.capture.DailyCaptureId
import com.fitlake.daily.domain.capture.DailyFoodItemSourceType
import com.fitlake.shared.application.elapsedMilliseconds
import com.fitlake.shared.logging.sanitizedForTechnicalLogging
import com.fitlake.user.application.UserQueryService
import com.fitlake.user.domain.UserId
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.time.LocalDate

@Service
class DailyAiMessageService(
	private val auditService: DailyAiAuditService,
	private val interpreter: CaptureInterpreterPort,
	private val terminalService: DailyAiTerminalService,
	private val userQueryService: UserQueryService,
	@Value("\${fitlake.daily.ai.max-text-length:4000}") private val maxTextLength: Int,
) {
	fun submitMessage(
		userId: UserId,
		date: LocalDate,
		idempotencyKey: String,
		text: String,
	): DailyAiResult {
		val request = validate(idempotencyKey, text)
		val timezone = userQueryService.requireById(userId).timezone
		return execute(
			auditService.prepareMessage(
				userId = userId,
				date = date,
				timezone = timezone,
				idempotencyKey = request.idempotencyKey,
				rawText = request.rawText,
				normalizedText = request.normalizedText,
				metadata = interpreter.metadata,
			),
		)
	}

	fun reprocess(
		userId: UserId,
		captureId: DailyCaptureId,
		idempotencyKey: String,
		text: String,
	): DailyAiResult {
		val request = validate(idempotencyKey, text)
		val timezone = userQueryService.requireById(userId).timezone
		return execute(
			auditService.prepareReprocess(
				userId = userId,
				captureId = captureId,
				timezone = timezone,
				idempotencyKey = request.idempotencyKey,
				rawText = request.rawText,
				normalizedText = request.normalizedText,
				metadata = interpreter.metadata,
			),
		)
	}

	private fun execute(preparation: DailyAiPreparation): DailyAiResult = when (preparation) {
		is DailyAiPreparation.Replay -> preparation.result
		is DailyAiPreparation.Execute -> executeFresh(preparation)
	}

	private fun executeFresh(preparation: DailyAiPreparation.Execute): DailyAiResult {
		val startedAtNanos = System.nanoTime()
		val text = preparation.persistedRawText
		var interpreted: InterpretedDailyMessage? = null
		try {
			interpreted = interpreter.interpret(
					InterpretDailyMessageRequest(
						targetDate = preparation.context.date,
						timezone = preparation.context.timezone,
						text = text,
					),
				)
			val result = terminalService.complete(preparation.context, text, interpreted)
			logCompleted(preparation.context, interpreted, result, startedAtNanos)
			return result
		} catch (exception: DailyAiException) {
			val rejected = exception is DailyAiOperationInProgressException ||
				exception is DailyAiIdempotencyConflictException
			recordAndLogFailure(
				context = preparation.context,
				status = if (exception is DailyAiInvalidOutputException) {
					AiInterpretationStatus.INVALID_OUTPUT
				} else {
					AiInterpretationStatus.FAILED
				},
				errorCode = exception.errorCode,
				errorMessage = exception.safeMessage,
				startedAtNanos = startedAtNanos,
				interpreted = interpreted,
				exception = exception,
				rejected = rejected,
				includeSanitizedStack = exception is DailyAiPersistenceException,
			)
			throw exception
		} catch (exception: DailyConflictException) {
			recordAndLogFailure(
				context = preparation.context,
				status = AiInterpretationStatus.FAILED,
				errorCode = "DAILY_CONFLICT",
				errorMessage = "Daily state changed while the AI request was processed",
				startedAtNanos = startedAtNanos,
				interpreted = interpreted,
				exception = exception,
				rejected = true,
			)
			throw exception
		} catch (exception: DailyNotFoundException) {
			recordAndLogFailure(
				context = preparation.context,
				status = AiInterpretationStatus.FAILED,
				errorCode = "DAILY_NOT_FOUND",
				errorMessage = "A Daily resource changed while the AI request was processed",
				startedAtNanos = startedAtNanos,
				interpreted = interpreted,
				exception = exception,
				rejected = true,
			)
			throw exception
		} catch (exception: DailyValidationException) {
			val invalid = DailyAiInvalidOutputException(exception)
			recordAndLogFailure(
				context = preparation.context,
				status = AiInterpretationStatus.INVALID_OUTPUT,
				errorCode = invalid.errorCode,
				errorMessage = invalid.safeMessage,
				startedAtNanos = startedAtNanos,
				interpreted = interpreted,
				exception = invalid,
			)
			throw invalid
		} catch (exception: RuntimeException) {
			val failure = DailyAiRecordedFailureException(
				errorCode = "AI_PROCESSING_FAILED",
				safeMessage = "The Daily AI request could not be completed",
				cause = exception,
			)
			recordAndLogFailure(
				context = preparation.context,
				status = AiInterpretationStatus.FAILED,
				errorCode = failure.errorCode,
				errorMessage = failure.safeMessage,
				startedAtNanos = startedAtNanos,
				interpreted = interpreted,
				exception = exception,
				includeSanitizedStack = true,
			)
			throw failure
		}
	}

	private fun logCompleted(
		context: DailyAiRequestContext,
		interpreted: InterpretedDailyMessage,
		result: DailyAiResult,
		startedAtNanos: Long,
	) {
		val capture = (result as? DailyAiResult.CaptureCreated)?.capture
		val foodItems = capture?.payload?.entries?.flatMap { it.items }.orEmpty()
		val personalFoodMatchCount = foodItems.count { it.sourceType == DailyFoodItemSourceType.USER_FOOD }
		val aiFallbackCount = foodItems.count { it.sourceType == DailyFoodItemSourceType.AI_ESTIMATE }
		val builder = logger.atInfo()
			.addKeyValue("event", "daily_ai_interpretation_completed")
			.addKeyValue("outcome", if (result is DailyAiResult.NoRelevantData) "no_op" else "success")
			.addKeyValue("userRef", context.userId.value)
			.addKeyValue("inboxEventId", context.inboxEventId.value)
			.addKeyValue("provider", context.metadata.provider)
			.addKeyValue("model", context.metadata.model)
			.addKeyValue("promptVersion", context.metadata.promptVersion)
			.addKeyValue("interpretationStatus", interpreted.interpretation.outcome)
			.addKeyValue("foodMentionCount", interpreted.interpretation.meals.sumOf { it.items.size })
			.addKeyValue("personalFoodMatchCount", personalFoodMatchCount)
			.addKeyValue("aiFallbackCount", aiFallbackCount)
			.addKeyValue("unresolvedFragmentCount", interpreted.interpretation.unresolvedFragments.size)
			.addKeyValue("retryCount", interpreted.retryCount)
			.addKeyValue("durationMs", elapsedMilliseconds(startedAtNanos))
		capture?.let { value ->
			builder
				.addKeyValue("captureId", value.captureId.value)
				.addKeyValue("entryCount", value.payload.entries.size)
				.addKeyValue("foodItemCount", foodItems.size)
		}
		interpreted.inputTokens?.let { builder.addKeyValue("inputTokenCount", it) }
		interpreted.outputTokens?.let { builder.addKeyValue("outputTokenCount", it) }
		builder.log("Daily AI interpretation completed")

		if (capture != null && aiFallbackCount > 0) {
			logger.atInfo()
				.addKeyValue("event", "daily_ai_estimate_fallback_used")
				.addKeyValue("outcome", "fallback")
				.addKeyValue("userRef", context.userId.value)
				.addKeyValue("inboxEventId", context.inboxEventId.value)
				.addKeyValue("captureId", capture.captureId.value)
				.addKeyValue("provider", context.metadata.provider)
				.addKeyValue("model", context.metadata.model)
				.addKeyValue("aiFallbackCount", aiFallbackCount)
				.log("Daily AI nutrition fallback used")
		}
	}

	private fun recordAndLogFailure(
		context: DailyAiRequestContext,
		status: AiInterpretationStatus,
		errorCode: String,
		errorMessage: String,
		startedAtNanos: Long,
		interpreted: InterpretedDailyMessage?,
		exception: RuntimeException,
		rejected: Boolean = false,
		includeSanitizedStack: Boolean = false,
	) {
		auditService.recordFailure(context, status, errorCode, errorMessage)
		val builder = if (rejected) logger.atWarn() else logger.atError()
		builder
			.addKeyValue("event", "daily_ai_interpretation_failed")
			.addKeyValue("outcome", if (rejected) "rejected" else "failure")
			.addKeyValue("errorCode", errorCode)
			.addKeyValue("userRef", context.userId.value)
			.addKeyValue("inboxEventId", context.inboxEventId.value)
			.addKeyValue("provider", context.metadata.provider)
			.addKeyValue("model", context.metadata.model)
			.addKeyValue("promptVersion", context.metadata.promptVersion)
			.addKeyValue("interpretationStatus", status)
			.addKeyValue("exceptionType", exception.javaClass.name)
			.addKeyValue("durationMs", elapsedMilliseconds(startedAtNanos))
		interpreted?.let { value ->
			builder.addKeyValue("retryCount", value.retryCount)
			value.inputTokens?.let { builder.addKeyValue("inputTokenCount", it) }
			value.outputTokens?.let { builder.addKeyValue("outputTokenCount", it) }
		}
		if (includeSanitizedStack) builder.setCause(exception.sanitizedForTechnicalLogging())
		builder.log("Daily AI interpretation failed")
	}

	private fun validate(idempotencyKey: String, text: String): ValidatedRequest {
		val normalizedKey = idempotencyKey.trim()
		if (normalizedKey.isEmpty()) {
			throw DailyValidationException("Idempotency-Key must not be blank")
		}
		if (normalizedKey.length > 200) {
			throw DailyValidationException("Idempotency-Key must not exceed 200 characters")
		}
		if (text.isBlank()) {
			throw DailyValidationException("Message text must not be blank")
		}
		if (text.length > maxTextLength) {
			throw DailyValidationException("Message text must not exceed $maxTextLength characters")
		}
		return ValidatedRequest(
			idempotencyKey = normalizedKey,
			rawText = text,
			normalizedText = text.trim().replace(WHITESPACE, " "),
		)
	}

	private data class ValidatedRequest(
		val idempotencyKey: String,
		val rawText: String,
		val normalizedText: String,
	)

	companion object {
		private val WHITESPACE = Regex("\\s+")
		private val logger = LoggerFactory.getLogger(DailyAiMessageService::class.java)
	}
}
