package com.fitlake.daily.adapter.rest

import com.fitlake.daily.application.ai.DailyAiResult
import com.fitlake.daily.domain.capture.DailyCaptureActor
import com.fitlake.daily.domain.capture.DailyCaptureStatus
import com.fitlake.daily.domain.capture.DailyCaptureType
import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.NotBlank
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

data class DailyTextMessageRequest(
	@field:NotBlank(message = "text must not be blank")
	@field:Schema(
		description = "Complete standalone text. The configured server limit is 4000 characters by default.",
	)
	val text: String,
)

enum class DailyAiRestOutcome {
	CAPTURE_CREATED,
	CAPTURE_REPLACED,
	CLARIFICATION_REQUIRED,
	NO_OP,
}

data class DailyAiCaptureResponse(
	val captureId: UUID,
	val dayId: UUID,
	val date: LocalDate,
	val type: DailyCaptureType,
	val status: DailyCaptureStatus,
	val payload: DailyCapturePayloadResponse,
	val createdBy: DailyCaptureActor,
	val createdAt: Instant,
	val version: Long,
)

data class DailyAiMessageResponse(
	val outcome: DailyAiRestOutcome,
	val replacedCaptureId: UUID? = null,
	val capture: DailyAiCaptureResponse? = null,
	val question: String? = null,
	val reason: String? = null,
)

fun DailyAiResult.toResponse(): DailyAiMessageResponse = when (this) {
	is DailyAiResult.CaptureCreated -> {
		DailyAiMessageResponse(
			outcome = if (replacedCaptureId == null) {
				DailyAiRestOutcome.CAPTURE_CREATED
			} else {
				DailyAiRestOutcome.CAPTURE_REPLACED
			},
			replacedCaptureId = replacedCaptureId?.value,
			capture = DailyAiCaptureResponse(
				captureId = capture.captureId.value,
				dayId = capture.dayId.value,
				date = date,
				type = capture.captureType,
				status = capture.status,
				payload = capture.payload.toResponse(),
				createdBy = capture.createdBy,
				createdAt = capture.createdAt,
				version = capture.version,
			),
		)
	}
	is DailyAiResult.ClarificationRequired -> DailyAiMessageResponse(
		outcome = DailyAiRestOutcome.CLARIFICATION_REQUIRED,
		question = question,
	)
	is DailyAiResult.NoOp -> DailyAiMessageResponse(
		outcome = DailyAiRestOutcome.NO_OP,
		reason = reason,
	)
}
