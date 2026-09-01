package com.fitlake.daily.adapter.rest

import com.fitlake.auth.application.CurrentUserProvider
import com.fitlake.daily.application.ai.DailyAiMessageService
import com.fitlake.daily.domain.capture.DailyCaptureId
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.ExampleObject
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import org.springframework.format.annotation.DateTimeFormat
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.LocalDate
import java.util.UUID

@RestController
@RequestMapping("/api/daily")
@Validated
@Tag(name = "Daily AI", description = "Standalone natural-language interpretation with backend-owned persistence")
class DailyAiController(
	private val currentUserProvider: CurrentUserProvider,
	private val messageService: DailyAiMessageService,
) {
	@PostMapping("/days/{date}/messages")
	@Operation(
		summary = "Interpret a standalone Daily message",
		description = "Interprets one complete standalone message without conversation memory. COMPLETE, PARTIAL and " +
			"UNRESOLVED each create one backend-validated OPEN capture; PARTIAL preserves unresolved fragments as NOTE " +
			"entries and UNRESOLVED preserves the complete original text. NO_RELEVANT_DATA creates no capture. Repeating " +
			"the same normalized text with the same Idempotency-Key replays its terminal result without another model call. " +
			"A capture replay reflects its current lifecycle state, payload, and version; different text conflicts.",
		requestBody = io.swagger.v3.oas.annotations.parameters.RequestBody(
			content = [Content(mediaType = "application/json", examples = [
				ExampleObject(name = "Complete food message", value = DailyAiOpenApiExamples.COMPLETE_REQUEST),
				ExampleObject(name = "Partial message", value = DailyAiOpenApiExamples.PARTIAL_REQUEST),
				ExampleObject(name = "Unresolved message", value = DailyAiOpenApiExamples.UNRESOLVED_REQUEST),
				ExampleObject(name = "No relevant data", value = DailyAiOpenApiExamples.NO_RELEVANT_REQUEST),
			])],
		),
	)
	@ApiResponse(
		responseCode = "201",
		description = "A backend-validated OPEN capture was created, or the same capture was idempotently replayed in its current state",
		content = [Content(mediaType = "application/json", examples = [
			ExampleObject(name = "Complete capture", value = DailyAiOpenApiExamples.COMPLETE_CAPTURE_CREATED),
			ExampleObject(name = "Partial capture", value = DailyAiOpenApiExamples.PARTIAL_CAPTURE_CREATED),
			ExampleObject(name = "Unresolved capture", value = DailyAiOpenApiExamples.UNRESOLVED_CAPTURE_CREATED),
		])],
	)
	@ApiResponse(
		responseCode = "200",
		description = "The message contains no relevant Daily data and no capture was created",
		content = [Content(mediaType = "application/json", examples = [
			ExampleObject(name = "No relevant data", value = DailyAiOpenApiExamples.NO_RELEVANT_DATA),
		])],
	)
	@ApiResponse(
		responseCode = "400",
		description = "The date, idempotency key, request body, or text is invalid",
		content = [Content(mediaType = "application/json", examples = [
			ExampleObject(name = "Blank text", value = DailyAiOpenApiExamples.BLANK_TEXT_ERROR),
			ExampleObject(name = "Invalid parameter", value = DailyAiOpenApiExamples.INVALID_PARAMETER_ERROR),
		])],
	)
	@ApiResponse(
		responseCode = "401",
		description = "Authentication is missing or invalid",
		content = [Content(mediaType = "application/json", examples = [
			ExampleObject(name = "Unauthorized", value = DailyAiOpenApiExamples.UNAUTHORIZED_ERROR),
		])],
	)
	@ApiResponse(
		responseCode = "409",
		description = "Idempotency key reuse, in-progress operation, or non-editable Daily state",
		content = [Content(mediaType = "application/json", examples = [
			ExampleObject(name = "Idempotency conflict", value = DailyAiOpenApiExamples.IDEMPOTENCY_CONFLICT_ERROR),
			ExampleObject(name = "Operation in progress", value = DailyAiOpenApiExamples.OPERATION_IN_PROGRESS_ERROR),
		])],
	)
	@ApiResponse(
		responseCode = "502",
		description = "The AI provider returned output that violates the structured contract",
		content = [Content(mediaType = "application/json", examples = [
			ExampleObject(name = "Invalid AI output", value = DailyAiOpenApiExamples.INVALID_OUTPUT_ERROR),
		])],
	)
	@ApiResponse(
		responseCode = "503",
		description = "Daily AI is not configured or its provider is unavailable",
		content = [Content(mediaType = "application/json", examples = [
			ExampleObject(name = "AI not configured", value = DailyAiOpenApiExamples.NOT_CONFIGURED_ERROR),
			ExampleObject(name = "Provider unavailable", value = DailyAiOpenApiExamples.PROVIDER_UNAVAILABLE_ERROR),
		])],
	)
	@ApiResponse(
		responseCode = "504",
		description = "The AI provider timed out",
		content = [Content(mediaType = "application/json", examples = [
			ExampleObject(name = "Provider timeout", value = DailyAiOpenApiExamples.TIMEOUT_ERROR),
		])],
	)
	@ApiResponse(
		responseCode = "500",
		description = "Daily AI processing or persistence failed",
		content = [Content(mediaType = "application/json", examples = [
			ExampleObject(name = "Processing failed", value = DailyAiOpenApiExamples.INTERNAL_ERROR),
		])],
	)
	fun submitMessage(
		@PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) date: LocalDate,
		@RequestHeader("Idempotency-Key")
		@Parameter(
			description = "Client-generated key unique for this complete message",
			required = true,
			example = "daily-message-20260731-001",
		)
		@NotBlank @Size(max = 200) idempotencyKey: String,
		@Valid @RequestBody request: DailyTextMessageRequest,
	): ResponseEntity<DailyAiMessageResponse> = messageService
		.submitMessage(currentUserId(), date, idempotencyKey, request.text)
		.toHttpResponse()

	@PostMapping("/captures/{captureId}/reprocess")
	@Operation(
		summary = "Reinterpret the complete text for an OPEN capture",
		description = "Accepts complete replacement text for an owned OPEN proposal on an OPEN or REOPENED day. On " +
			"success it creates a distinct OPEN capture and atomically marks the previous proposal REJECTED. " +
			"NO_RELEVANT_DATA and failed processing leave the previous capture OPEN and unchanged. An idempotent replay " +
			"returns the replacement capture in its current lifecycle state without invoking the model again.",
		requestBody = io.swagger.v3.oas.annotations.parameters.RequestBody(
			content = [Content(mediaType = "application/json", examples = [
				ExampleObject(name = "Complete replacement text", value = DailyAiOpenApiExamples.REPROCESS_REQUEST),
			])],
		),
	)
	@ApiResponse(
		responseCode = "201",
		description = "A replacement OPEN capture was created and the previous proposal rejected, or that replacement was replayed in its current state",
		content = [Content(mediaType = "application/json", examples = [
			ExampleObject(name = "Replacement capture", value = DailyAiOpenApiExamples.CAPTURE_REPLACED),
		])],
	)
	@ApiResponse(
		responseCode = "200",
		description = "NO_RELEVANT_DATA; no replacement was created and the previous proposal remains OPEN",
		content = [Content(mediaType = "application/json", examples = [
			ExampleObject(name = "No relevant data", value = DailyAiOpenApiExamples.NO_RELEVANT_DATA),
		])],
	)
	@ApiResponse(
		responseCode = "400",
		description = "The idempotency key, capture ID, request body, or text is invalid",
		content = [Content(mediaType = "application/json", examples = [
			ExampleObject(name = "Blank text", value = DailyAiOpenApiExamples.BLANK_TEXT_ERROR),
			ExampleObject(name = "Invalid parameter", value = DailyAiOpenApiExamples.INVALID_PARAMETER_ERROR),
		])],
	)
	@ApiResponse(
		responseCode = "401",
		description = "Authentication is missing or invalid",
		content = [Content(mediaType = "application/json", examples = [
			ExampleObject(name = "Unauthorized", value = DailyAiOpenApiExamples.UNAUTHORIZED_ERROR),
		])],
	)
	@ApiResponse(
		responseCode = "404",
		description = "The capture was not found or is owned by another user",
		content = [Content(mediaType = "application/json", examples = [
			ExampleObject(name = "Capture not found", value = DailyAiOpenApiExamples.CAPTURE_NOT_FOUND_ERROR),
		])],
	)
	@ApiResponse(
		responseCode = "409",
		description = "The capture/day is not reprocessable, the idempotency key conflicts, or the operation is in progress",
		content = [Content(mediaType = "application/json", examples = [
			ExampleObject(name = "Capture not open", value = DailyAiOpenApiExamples.NOT_REPROCESSABLE_ERROR),
			ExampleObject(name = "Idempotency conflict", value = DailyAiOpenApiExamples.IDEMPOTENCY_CONFLICT_ERROR),
			ExampleObject(name = "Operation in progress", value = DailyAiOpenApiExamples.OPERATION_IN_PROGRESS_ERROR),
		])],
	)
	@ApiResponse(
		responseCode = "502",
		description = "The AI provider returned output that violates the structured contract",
		content = [Content(mediaType = "application/json", examples = [
			ExampleObject(name = "Invalid AI output", value = DailyAiOpenApiExamples.INVALID_OUTPUT_ERROR),
		])],
	)
	@ApiResponse(
		responseCode = "503",
		description = "Daily AI is not configured or its provider is unavailable",
		content = [Content(mediaType = "application/json", examples = [
			ExampleObject(name = "AI not configured", value = DailyAiOpenApiExamples.NOT_CONFIGURED_ERROR),
			ExampleObject(name = "Provider unavailable", value = DailyAiOpenApiExamples.PROVIDER_UNAVAILABLE_ERROR),
		])],
	)
	@ApiResponse(
		responseCode = "504",
		description = "The AI provider timed out",
		content = [Content(mediaType = "application/json", examples = [
			ExampleObject(name = "Provider timeout", value = DailyAiOpenApiExamples.TIMEOUT_ERROR),
		])],
	)
	@ApiResponse(
		responseCode = "500",
		description = "Daily AI processing or persistence failed",
		content = [Content(mediaType = "application/json", examples = [
			ExampleObject(name = "Processing failed", value = DailyAiOpenApiExamples.INTERNAL_ERROR),
		])],
	)
	fun reprocess(
		@PathVariable captureId: UUID,
		@RequestHeader("Idempotency-Key")
		@Parameter(
			description = "Client-generated key unique for this complete reprocess request",
			required = true,
			example = "daily-reprocess-20260731-001",
		)
		@NotBlank @Size(max = 200) idempotencyKey: String,
		@Valid @RequestBody request: DailyTextMessageRequest,
	): ResponseEntity<DailyAiMessageResponse> = messageService
		.reprocess(currentUserId(), DailyCaptureId(captureId), idempotencyKey, request.text)
		.toHttpResponse()

	private fun currentUserId() = currentUserProvider.requireCurrentUser().userId

	private fun com.fitlake.daily.application.ai.DailyAiResult.toHttpResponse(): ResponseEntity<DailyAiMessageResponse> {
		val response = toResponse()
		val status = when (response.outcome) {
			DailyAiRestOutcome.CAPTURE_CREATED,
			DailyAiRestOutcome.CAPTURE_REPLACED,
			-> HttpStatus.CREATED
			DailyAiRestOutcome.NO_RELEVANT_DATA,
			-> HttpStatus.OK
		}
		return ResponseEntity.status(status).body(response)
	}
}
