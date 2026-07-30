package com.fitlake.daily.adapter.rest

import com.fitlake.auth.application.CurrentUserProvider
import com.fitlake.daily.application.ai.DailyAiMessageService
import com.fitlake.daily.domain.capture.DailyCaptureId
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
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
@Tag(name = "Daily AI", description = "Standalone natural-language insertion through terminal AI tools")
class DailyAiController(
	private val currentUserProvider: CurrentUserProvider,
	private val messageService: DailyAiMessageService,
) {
	@PostMapping("/days/{date}/messages")
	@Operation(
		summary = "Interpret a standalone Daily message",
		description = "Requires a unique Idempotency-Key. Creates an OPEN capture, asks one clarification, or returns NO_OP.",
	)
	@ApiResponse(responseCode = "201", description = "An OPEN capture was created")
	@ApiResponse(responseCode = "200", description = "Clarification or NO_OP")
	@ApiResponse(responseCode = "400", description = "The request, date, idempotency key, or text is invalid")
	@ApiResponse(responseCode = "401", description = "Authentication is missing or invalid")
	@ApiResponse(responseCode = "404", description = "The requested Daily resource was not found")
	@ApiResponse(responseCode = "409", description = "Idempotency or Daily state conflict")
	@ApiResponse(responseCode = "502", description = "The AI provider returned an invalid tool result")
	@ApiResponse(responseCode = "503", description = "Daily AI is not configured or the provider is unavailable")
	@ApiResponse(responseCode = "504", description = "The AI provider timed out")
	@ApiResponse(responseCode = "500", description = "Daily AI processing or persistence failed")
	fun submitMessage(
		@PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) date: LocalDate,
		@RequestHeader("Idempotency-Key")
		@Parameter(description = "Client-generated key unique for this complete message", required = true)
		@NotBlank @Size(max = 200) idempotencyKey: String,
		@Valid @RequestBody request: DailyTextMessageRequest,
	): ResponseEntity<DailyAiMessageResponse> = messageService
		.submitMessage(currentUserId(), date, idempotencyKey, request.text)
		.toHttpResponse()

	@PostMapping("/captures/{captureId}/reprocess")
	@Operation(
		summary = "Reinterpret the complete text for an OPEN capture",
		description = "On success creates a new OPEN capture and atomically marks the previous proposal REJECTED.",
	)
	@ApiResponse(responseCode = "201", description = "The previous proposal was replaced")
	@ApiResponse(responseCode = "200", description = "Clarification or NO_OP; the previous proposal remains OPEN")
	@ApiResponse(responseCode = "400", description = "The request, idempotency key, capture ID, or text is invalid")
	@ApiResponse(responseCode = "401", description = "Authentication is missing or invalid")
	@ApiResponse(responseCode = "404", description = "The capture was not found or is owned by another user")
	@ApiResponse(responseCode = "409", description = "The capture, day, idempotency key, or concurrent state is not reprocessable")
	@ApiResponse(responseCode = "502", description = "The AI provider returned an invalid tool result")
	@ApiResponse(responseCode = "503", description = "Daily AI is not configured or the provider is unavailable")
	@ApiResponse(responseCode = "504", description = "The AI provider timed out")
	@ApiResponse(responseCode = "500", description = "Daily AI processing or persistence failed")
	fun reprocess(
		@PathVariable captureId: UUID,
		@RequestHeader("Idempotency-Key")
		@Parameter(description = "Client-generated key unique for this complete reprocess request", required = true)
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
			DailyAiRestOutcome.CLARIFICATION_REQUIRED,
			DailyAiRestOutcome.NO_OP,
			-> HttpStatus.OK
		}
		return ResponseEntity.status(status).body(response)
	}
}
