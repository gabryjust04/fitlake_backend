package com.fitlake.daily.adapter.rest

import com.fitlake.auth.application.CurrentUserProvider
import com.fitlake.daily.application.DailyQueryService
import com.fitlake.daily.application.capture.CaptureConfirmationService
import com.fitlake.daily.application.capture.DailyCaptureEditService
import com.fitlake.daily.application.capture.DailyCaptureService
import com.fitlake.daily.application.finalization.DailyFinalizationService
import com.fitlake.daily.domain.capture.DailyCaptureId
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.format.annotation.DateTimeFormat
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.time.LocalDate
import java.util.UUID

@RestController
@RequestMapping("/api/daily")
@Tag(name = "Daily", description = "Manual Daily tracking without AI or Telegram")
class DailyController(
	private val currentUserProvider: CurrentUserProvider,
	private val captureService: DailyCaptureService,
	private val confirmationService: CaptureConfirmationService,
	private val editService: DailyCaptureEditService,
	private val finalizationService: DailyFinalizationService,
	private val queryService: DailyQueryService,
) {
	@PostMapping("/days/{date}/captures")
	@ResponseStatus(HttpStatus.CREATED)
	@Operation(summary = "Create an open manual capture")
	fun createCapture(
		@PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) date: LocalDate,
		@Valid @RequestBody request: DailyCaptureRequest,
	): DailyCaptureResponse = captureService.create(currentUserId(), date, request.toInput()).toResponse()

	@GetMapping("/days/{date}")
	@Operation(summary = "Read a day with captures and finalized metrics")
	fun getDay(
		@PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) date: LocalDate,
	): DailyDayResponse = queryService.getDay(currentUserId(), date).toResponse()

	@GetMapping("/days/{date}/metrics")
	@Operation(summary = "Read finalized daily metrics")
	fun getMetrics(
		@PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) date: LocalDate,
	): DailyMetricsResponse = queryService.getMetrics(currentUserId(), date).toResponse()

	@PostMapping("/captures/{captureId}/accept")
	@Operation(summary = "Accept an open capture")
	fun acceptCapture(@PathVariable captureId: UUID): DailyCaptureResponse =
		confirmationService.accept(currentUserId(), DailyCaptureId(captureId)).toResponse()

	@PostMapping("/captures/{captureId}/reject")
	@Operation(summary = "Reject an open capture")
	fun rejectCapture(@PathVariable captureId: UUID): DailyCaptureResponse =
		confirmationService.reject(currentUserId(), DailyCaptureId(captureId)).toResponse()

	@PutMapping("/captures/{captureId}")
	@Operation(summary = "Replace an open or accepted capture payload")
	fun replaceCapture(
		@PathVariable captureId: UUID,
		@Valid @RequestBody request: DailyCaptureRequest,
	): DailyCaptureResponse = editService
		.replace(currentUserId(), DailyCaptureId(captureId), request.toInput())
		.toResponse()

	@PatchMapping("/captures/{captureId}/food-items/{itemTempId}")
	@Operation(summary = "Update quantity and unit of a food item")
	fun updateFoodItem(
		@PathVariable captureId: UUID,
		@PathVariable itemTempId: String,
		@Valid @RequestBody request: UpdateFoodItemRequest,
	): DailyCaptureResponse = editService.updateFoodItem(
		userId = currentUserId(),
		captureId = DailyCaptureId(captureId),
		itemTempId = itemTempId,
		quantity = request.quantity,
		unit = request.unit,
	).toResponse()

	@DeleteMapping("/captures/{captureId}")
	@Operation(summary = "Soft delete a capture")
	fun softDeleteCapture(@PathVariable captureId: UUID): DailyCaptureResponse =
		editService.softDelete(currentUserId(), DailyCaptureId(captureId)).toResponse()

	@PostMapping("/days/{date}/finalize")
	@Operation(summary = "Finalize a day from accepted captures")
	fun finalizeDay(
		@PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) date: LocalDate,
	): DailyMetricsResponse = finalizationService.finalizeDay(currentUserId(), date).toResponse()

	private fun currentUserId() = currentUserProvider.requireCurrentUser().userId
}
