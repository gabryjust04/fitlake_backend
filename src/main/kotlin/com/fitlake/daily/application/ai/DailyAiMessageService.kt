package com.fitlake.daily.application.ai

import com.fitlake.daily.application.DailyConflictException
import com.fitlake.daily.application.DailyNotFoundException
import com.fitlake.daily.application.DailyValidationException
import com.fitlake.daily.domain.ai.AiInterpretationStatus
import com.fitlake.daily.domain.capture.DailyCaptureId
import com.fitlake.user.application.UserQueryService
import com.fitlake.user.domain.UserId
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.time.LocalDate

@Service
class DailyAiMessageService(
	private val auditService: DailyAiAuditService,
	private val interpreter: DailyAiInterpreter,
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
			request.rawText,
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
			request.rawText,
		)
	}

	private fun execute(preparation: DailyAiPreparation, text: String): DailyAiResult = when (preparation) {
		is DailyAiPreparation.Replay -> preparation.result
		is DailyAiPreparation.Execute -> {
			try {
				interpreter.interpret(preparation.context, text)
			} catch (exception: DailyAiException) {
				auditService.recordFailure(
					preparation.context,
					if (exception is DailyAiInvalidOutputException) {
						AiInterpretationStatus.INVALID_OUTPUT
					} else {
						AiInterpretationStatus.FAILED
					},
					exception.errorCode,
					exception.safeMessage,
				)
				throw exception
			} catch (exception: DailyConflictException) {
				auditService.recordFailure(
					preparation.context,
					AiInterpretationStatus.FAILED,
					"DAILY_CONFLICT",
					"Daily state changed while the AI request was processed",
				)
				throw exception
			} catch (exception: DailyNotFoundException) {
				auditService.recordFailure(
					preparation.context,
					AiInterpretationStatus.FAILED,
					"DAILY_NOT_FOUND",
					"A Daily resource changed while the AI request was processed",
				)
				throw exception
			} catch (exception: DailyValidationException) {
				val invalid = DailyAiInvalidOutputException(exception)
				auditService.recordFailure(
					preparation.context,
					AiInterpretationStatus.INVALID_OUTPUT,
					invalid.errorCode,
					invalid.safeMessage,
				)
				throw invalid
			} catch (exception: RuntimeException) {
				val failure = DailyAiRecordedFailureException(
					errorCode = "AI_PROCESSING_FAILED",
					safeMessage = "The Daily AI request could not be completed",
				)
				auditService.recordFailure(
					preparation.context,
					AiInterpretationStatus.FAILED,
					failure.errorCode,
					failure.safeMessage,
				)
				throw failure
			}
		}
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
	}
}
