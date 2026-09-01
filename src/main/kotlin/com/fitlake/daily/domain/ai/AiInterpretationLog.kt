package com.fitlake.daily.domain.ai

import com.fitlake.daily.domain.capture.DailyCaptureId
import com.fitlake.daily.domain.inbox.DailyInboxEventId
import com.fitlake.user.domain.UserId
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

@JvmInline
value class AiInterpretationLogId(val value: UUID)

enum class AiInterpretationStatus {
	SUCCESS,
	FAILED,
	INVALID_OUTPUT,
	NO_RELEVANT_DATA,
}

data class AiInterpretationLog(
	val aiLogId: AiInterpretationLogId,
	val userId: UserId,
	val inboxEventId: DailyInboxEventId,
	val captureId: DailyCaptureId?,
	val provider: String,
	val model: String,
	val promptVersion: String,
	val inputText: String?,
	val contextSnapshot: Map<String, Any?>,
	val parsedOutput: Map<String, Any?>,
	val status: AiInterpretationStatus,
	val confidence: BigDecimal?,
	val errorCode: String?,
	val errorMessage: String?,
	val latencyMs: Int?,
	val createdAt: Instant,
) {
	init {
		require(provider.isNotBlank()) { "AI provider must not be blank" }
		require(model.isNotBlank()) { "AI model must not be blank" }
		require(promptVersion.isNotBlank()) { "AI prompt version must not be blank" }
		require(inputText == null || inputText.isNotBlank()) { "AI input text must be null or non-blank" }
		require(confidence == null || confidence in BigDecimal.ZERO..BigDecimal.ONE) {
			"AI confidence must be between 0 and 1"
		}
		require(latencyMs == null || latencyMs >= 0) { "AI latency must not be negative" }
		require(errorCode == null || errorCode.isNotBlank()) { "AI error code must be null or non-blank" }
		require(errorMessage == null || errorMessage.isNotBlank()) { "AI error message must be null or non-blank" }
	}
}
