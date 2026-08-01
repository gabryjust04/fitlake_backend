package com.fitlake.daily.adapter.rest

import com.fitlake.auth.application.CurrentUserProvider
import com.fitlake.daily.application.DailyQueryService
import com.fitlake.daily.application.DailyValidationException
import com.fitlake.daily.application.capture.CaptureConfirmationService
import com.fitlake.daily.application.capture.DailyCaptureEditService
import com.fitlake.daily.application.capture.DailyManualCaptureService
import com.fitlake.daily.application.finalization.DailyDayReopeningService
import com.fitlake.daily.application.finalization.DailyFinalizationService
import com.fitlake.daily.domain.capture.DailyCaptureId
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.ExampleObject
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.format.annotation.DateTimeFormat
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
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
	private val manualCaptureService: DailyManualCaptureService,
	private val confirmationService: CaptureConfirmationService,
	private val editService: DailyCaptureEditService,
	private val finalizationService: DailyFinalizationService,
	private val reopeningService: DailyDayReopeningService,
	private val queryService: DailyQueryService,
) {
	@PostMapping("/days/{date}/captures")
	@ResponseStatus(HttpStatus.CREATED)
	@Operation(
		summary = "Create an open manual capture",
		description = "For linked food entries, first search GET /api/me/foods/search, then submit the exact " +
			"userFoodId. Daily performs deterministic conversion and stores an immutable nutrition snapshot; no AI is used. " +
			"Supported food units: GRAM, KILOGRAM, MILLILITER, LITER, PIECE, SERVING and DEFAULT_SERVING.",
		requestBody = io.swagger.v3.oas.annotations.parameters.RequestBody(
			content = [Content(examples = [
				ExampleObject(name = "One personal food", value = MANUAL_SINGLE_FOOD_EXAMPLE),
				ExampleObject(name = "Multiple personal foods", value = MANUAL_MULTIPLE_FOODS_EXAMPLE),
				ExampleObject(name = "Mixed food weight hydration", value = MANUAL_MIXED_EXAMPLE),
			])],
		),
		responses = [
			ApiResponse(
				responseCode = "201",
				description = "Capture created with backend IDs and calculated snapshot",
				content = [Content(examples = [ExampleObject(name = "Calculated capture", value = MANUAL_RESPONSE_EXAMPLE)])],
			),
			ApiResponse(
				responseCode = "400",
				description = "Invalid or incompatible quantity",
				content = [Content(examples = [
					ExampleObject(name = "Incompatible dimension", value = INCOMPATIBLE_UNIT_ERROR),
					ExampleObject(name = "Missing conversion", value = MISSING_CONVERSION_ERROR),
				])],
			),
			ApiResponse(responseCode = "404", description = "Personal food not found or not owned"),
		],
	)
	fun createCapture(
		@PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) date: LocalDate,
		@Valid @RequestBody request: DailyCaptureRequest,
	): DailyCaptureResponse {
		return manualCaptureService.create(currentUserId(), date, validated { request.toContentInput() }).toResponse()
	}

	@GetMapping("/captures/{captureId}")
	@Operation(summary = "Read one owned capture")
	fun getCapture(@PathVariable captureId: UUID): DailyCaptureResponse =
		queryService.getCapture(currentUserId(), DailyCaptureId(captureId)).toResponse()

	@GetMapping("/days/{date}/captures")
	@Operation(summary = "List captures for one owned day")
	fun getCaptures(
		@PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) date: LocalDate,
	): List<DailyCaptureResponse> = queryService.getCaptures(currentUserId(), date).map { it.toResponse() }

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
	@Operation(
		summary = "Atomically replace a complete capture",
		description = "The entries array is the complete new content: omitted entries/items are removed. Existing IDs must " +
			"belong to this capture; absent IDs are generated. The version is an optimistic-lock token. Unchanged items keep " +
			"their snapshot; quantity-only edits recalculate from it. New/changed food references must resolve to an active owned food.",
		requestBody = io.swagger.v3.oas.annotations.parameters.RequestBody(
			content = [Content(examples = [ExampleObject(name = "Full content replacement", value = REPLACE_CAPTURE_EXAMPLE)])],
		),
		responses = [
			ApiResponse(
				responseCode = "200",
				description = "Content replaced and version incremented",
				content = [Content(examples = [ExampleObject(name = "Updated calculated capture", value = MANUAL_RESPONSE_EXAMPLE)])],
			),
			ApiResponse(
				responseCode = "409",
				description = "Stale version or non-editable capture",
				content = [Content(examples = [ExampleObject(name = "Stale version", value = STALE_VERSION_ERROR)])],
			),
		],
	)
	fun replaceCapture(
		@PathVariable captureId: UUID,
		@Valid @RequestBody request: ReplaceDailyCaptureRequest,
		@RequestHeader(name = "X-Request-ID", required = false) requestId: String?,
	): DailyCaptureResponse = manualCaptureService.replace(
		userId = currentUserId(),
		captureId = DailyCaptureId(captureId),
		expectedVersion = validated { request.requiredVersion() },
		input = validated { request.toInput() },
		requestId = requestId,
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

	@PostMapping("/days/{date}/reopen")
	@Operation(
		summary = "Reopen a confirmed day",
		description = "Marks the day and its existing metrics snapshot as REOPENED. Captures can then be changed; " +
			"the next finalization recalculates and updates the same metrics row.",
	)
	fun reopenDay(
		@PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) date: LocalDate,
	): DailyDayResponse {
		val userId = currentUserId()
		reopeningService.reopenDay(userId, date)
		return queryService.getDay(userId, date).toResponse()
	}

	private fun currentUserId() = currentUserProvider.requireCurrentUser().userId

	private fun <T> validated(block: () -> T): T = try {
		block()
	} catch (exception: IllegalArgumentException) {
		throw DailyValidationException(exception.message ?: "Invalid daily capture request")
	}

	companion object {
		private const val MANUAL_SINGLE_FOOD_EXAMPLE = """
{
  "entries": [{
    "type": "FOOD",
    "mealType": "BREAKFAST",
    "items": [{
      "sourceType": "USER_FOOD",
      "userFoodId": "2db702d6-aeeb-46be-b863-72d552de63ab",
      "quantity": {"amount": 1, "unit": "DEFAULT_SERVING"}
    }]
  }]
}
"""
		private const val MANUAL_MULTIPLE_FOODS_EXAMPLE = """
{
  "entries": [{
    "type": "FOOD",
    "mealType": "DINNER",
    "items": [
      {"sourceType": "USER_FOOD", "userFoodId": "2db702d6-aeeb-46be-b863-72d552de63ab", "quantity": {"amount": 0.25, "unit": "KILOGRAM"}},
      {"sourceType": "USER_FOOD", "userFoodId": "b35a26e4-6901-4c64-bada-94db0b3ad6e8", "quantity": {"amount": 250, "unit": "MILLILITER"}},
      {"sourceType": "USER_FOOD", "userFoodId": "a58a72ae-1f5c-42fa-9a18-ffebd753e32d", "quantity": {"amount": 2, "unit": "PIECE"}}
    ]
  }]
}
"""
		private const val MANUAL_MIXED_EXAMPLE = """
{
  "entries": [
    {"type": "FOOD", "mealType": "DINNER", "items": [{"sourceType": "USER_FOOD", "userFoodId": "2db702d6-aeeb-46be-b863-72d552de63ab", "quantity": {"amount": 1, "unit": "SERVING"}}]},
    {"type": "WEIGHT", "value": 78, "unit": "KILOGRAM"},
    {"type": "HYDRATION", "value": 750, "unit": "MILLILITER"}
  ]
}
"""
		private const val REPLACE_CAPTURE_EXAMPLE = """
{
  "version": 4,
  "entries": [
    {
      "entryId": "16f39f26-5187-4aab-b7cc-0c0cc94421d2",
      "type": "FOOD",
      "mealType": "DINNER",
      "items": [{
        "itemId": "bb8a89aa-17d5-4240-971c-ef529adb8e9a",
        "sourceType": "USER_FOOD",
        "userFoodId": "2db702d6-aeeb-46be-b863-72d552de63ab",
        "quantity": {"amount": 200, "unit": "GRAM"}
      }]
    },
    {"type": "WEIGHT", "value": 77.8, "unit": "KILOGRAM"}
  ]
}
"""
		private const val MANUAL_RESPONSE_EXAMPLE = """
{
  "captureId": "5a25b934-1681-433b-ac83-f2a2936203ef",
  "dayId": "b3bb97aa-0b12-4f07-a008-e148f2ec7bf0",
  "captureType": "FOOD",
  "status": "OPEN",
  "payload": {
    "schemaVersion": 2,
    "entries": [{
      "entryId": "16f39f26-5187-4aab-b7cc-0c0cc94421d2",
      "type": "FOOD",
      "mealType": "BREAKFAST",
      "mealLabel": null,
      "items": [{
        "itemId": "bb8a89aa-17d5-4240-971c-ef529adb8e9a",
        "sourceType": "USER_FOOD",
        "userFoodId": "2db702d6-aeeb-46be-b863-72d552de63ab",
        "displayName": "My usual Greek yogurt",
        "brand": "Example Brand",
        "enteredQuantity": {"amount": 1, "unit": "DEFAULT_SERVING"},
        "resolvedQuantity": {"amount": 170, "unit": "GRAM"},
        "nutritionBasisSnapshot": {"amount": 100, "unit": "GRAM"},
        "nutrientsPerBasisSnapshot": {"caloriesKcal": 62, "proteinGrams": 9.5, "carbohydratesGrams": 4.1, "fatGrams": 0.2, "fiberGrams": null, "sugarsGrams": null, "saturatedFatGrams": null, "sodiumMilligrams": null, "saltGrams": null},
        "defaultServingSnapshot": {"amount": 170, "unit": "GRAM"},
        "conversionSnapshot": {"gramsPerPiece": null, "millilitersPerPiece": null, "gramsPerServing": null, "millilitersPerServing": null},
        "calculatedNutrition": {"caloriesKcal": 105.4, "proteinGrams": 16.15, "carbohydratesGrams": 6.97, "fatGrams": 0.34, "fiberGrams": null, "sugarsGrams": null, "saturatedFatGrams": null, "sodiumMilligrams": null, "saltGrams": null},
        "nutritionSourceSnapshot": {"type": "USER_FOOD", "originalSourceType": "PRODUCT_LABEL", "estimated": false, "provider": null, "externalId": null, "notes": "Copied manually from the package", "copiedAt": "2026-07-30"},
        "userFoodVersion": 3,
        "userFoodUpdatedAt": "2026-07-31T10:00:00Z"
      }],
      "value": null,
      "unit": null,
      "text": null,
      "nutritionTotal": {"caloriesKcal": 105.4, "proteinGrams": 16.15, "carbohydratesGrams": 6.97, "fatGrams": 0.34, "fiberGrams": null, "sugarsGrams": null, "saturatedFatGrams": null, "sodiumMilligrams": null, "saltGrams": null}
    }]
  },
  "createdBy": "USER_UI",
  "updatedBy": null,
  "acceptedAt": null,
  "rejectedAt": null,
  "deletedAt": null,
  "createdAt": "2026-07-31T12:00:00Z",
  "updatedAt": "2026-07-31T12:00:00Z",
  "version": 0
}
"""
		private const val INCOMPATIBLE_UNIT_ERROR = """
{"error":"validation_error","message":"Incompatible food quantity units: GRAM cannot be converted to MILLILITER","fieldErrors":{}}
"""
		private const val MISSING_CONVERSION_ERROR = """
{"error":"validation_error","message":"Piece-to-mass conversion requires gramsPerPiece","fieldErrors":{}}
"""
		private const val STALE_VERSION_ERROR = """
{"error":"conflict","message":"Capture version is stale","fieldErrors":{}}
"""
	}
}
