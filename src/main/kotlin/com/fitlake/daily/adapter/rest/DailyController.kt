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
import com.fitlake.shared.infrastructure.http.RequestCorrelationFilter
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.ArraySchema
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.ExampleObject
import io.swagger.v3.oas.annotations.media.Schema
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
import org.springframework.web.bind.annotation.RequestAttribute
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
			"New manual items must use USER_FOOD; clients cannot author AI_ESTIMATE data. Supported food units: " +
			"GRAM, KILOGRAM, MILLILITER, LITER, PIECE, SERVING and DEFAULT_SERVING.",
		requestBody = io.swagger.v3.oas.annotations.parameters.RequestBody(
			content = [Content(mediaType = "application/json", examples = [
				ExampleObject(name = "One personal food", value = MANUAL_SINGLE_FOOD_EXAMPLE),
				ExampleObject(name = "Multiple personal foods", value = MANUAL_MULTIPLE_FOODS_EXAMPLE),
				ExampleObject(name = "Mixed food weight hydration", value = MANUAL_MIXED_EXAMPLE),
			])],
		),
		responses = [
			ApiResponse(
				responseCode = "201",
				description = "Capture created with backend IDs and calculated snapshot",
				content = [Content(mediaType = "application/json", examples = [ExampleObject(name = "Calculated capture", value = MANUAL_RESPONSE_EXAMPLE)])],
			),
			ApiResponse(
				responseCode = "400",
				description = "Invalid or incompatible quantity",
				content = [Content(mediaType = "application/json", examples = [
					ExampleObject(name = "Incompatible dimension", value = INCOMPATIBLE_UNIT_ERROR),
					ExampleObject(name = "Missing conversion", value = MISSING_CONVERSION_ERROR),
				])],
			),
			ApiResponse(responseCode = "404", description = "Personal food not found or not owned"),
			ApiResponse(
				responseCode = "401",
				description = "Authentication is missing or invalid",
				content = [Content(mediaType = "application/json", examples = [
					ExampleObject(name = "Unauthorized", value = UNAUTHORIZED_ERROR),
				])],
			),
			ApiResponse(
				responseCode = "409",
				description = "The target day is already CONFIRMED",
				content = [Content(mediaType = "application/json", examples = [
					ExampleObject(name = "Confirmed day", value = CONFIRMED_DAY_CONFLICT_ERROR),
				])],
			),
		],
	)
	fun createCapture(
		@PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) date: LocalDate,
		@Valid @RequestBody request: DailyCaptureRequest,
	): DailyCaptureResponse {
		return manualCaptureService.create(currentUserId(), date, validated { request.toContentInput() }).toResponse()
	}

	@GetMapping("/captures/{captureId}")
	@Operation(
		summary = "Read one owned capture",
		responses = [
			ApiResponse(
				responseCode = "200",
				description = "Complete owned capture",
				content = [Content(
					mediaType = "application/json",
					schema = Schema(implementation = DailyCaptureResponse::class),
				)],
			),
			ApiResponse(
				responseCode = "401",
				description = "Authentication is missing or invalid",
				content = [Content(mediaType = "application/json", examples = [
					ExampleObject(name = "Unauthorized", value = UNAUTHORIZED_ERROR),
				])],
			),
			ApiResponse(
				responseCode = "404",
				description = "Capture not found or owned by another user",
				content = [Content(mediaType = "application/json", examples = [
					ExampleObject(name = "Capture not found", value = CAPTURE_NOT_FOUND_ERROR),
				])],
			),
		],
	)
	fun getCapture(@PathVariable captureId: UUID): DailyCaptureResponse =
		queryService.getCapture(currentUserId(), DailyCaptureId(captureId)).toResponse()

	@GetMapping("/days/{date}/captures")
	@Operation(
		summary = "List captures for one owned day",
		responses = [
			ApiResponse(
				responseCode = "200",
				description = "All captures for the owned day",
				content = [Content(
					mediaType = "application/json",
					array = ArraySchema(schema = Schema(implementation = DailyCaptureResponse::class)),
				)],
			),
			ApiResponse(
				responseCode = "401",
				description = "Authentication is missing or invalid",
				content = [Content(mediaType = "application/json", examples = [
					ExampleObject(name = "Unauthorized", value = UNAUTHORIZED_ERROR),
				])],
			),
			ApiResponse(
				responseCode = "404",
				description = "Day not found for the authenticated user",
				content = [Content(mediaType = "application/json", examples = [
					ExampleObject(name = "Day not found", value = DAY_NOT_FOUND_ERROR),
				])],
			),
		],
	)
	fun getCaptures(
		@PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) date: LocalDate,
	): List<DailyCaptureResponse> = queryService.getCaptures(currentUserId(), date).map { it.toResponse() }

	@GetMapping("/days/{date}")
	@Operation(
		summary = "Read a day with captures and finalized metrics",
		responses = [
			ApiResponse(
				responseCode = "200",
				description = "Owned day with captures and its metrics snapshot when present",
				content = [Content(
					mediaType = "application/json",
					schema = Schema(implementation = DailyDayResponse::class),
				)],
			),
			ApiResponse(
				responseCode = "401",
				description = "Authentication is missing or invalid",
				content = [Content(mediaType = "application/json", examples = [
					ExampleObject(name = "Unauthorized", value = UNAUTHORIZED_ERROR),
				])],
			),
			ApiResponse(
				responseCode = "404",
				description = "Day not found for the authenticated user",
				content = [Content(mediaType = "application/json", examples = [
					ExampleObject(name = "Day not found", value = DAY_NOT_FOUND_ERROR),
				])],
			),
		],
	)
	fun getDay(
		@PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) date: LocalDate,
	): DailyDayResponse = queryService.getDay(currentUserId(), date).toResponse()

	@GetMapping("/days/{date}/metrics")
	@Operation(
		summary = "Read the current daily metrics snapshot",
		description = "Returns CONFIRMED metrics, or the explicitly stale REOPENED snapshot after a day is reopened.",
		responses = [
			ApiResponse(
				responseCode = "200",
				description = "Current owned metrics snapshot",
				content = [Content(
					mediaType = "application/json",
					schema = Schema(implementation = DailyMetricsResponse::class),
				)],
			),
			ApiResponse(
				responseCode = "401",
				description = "Authentication is missing or invalid",
				content = [Content(mediaType = "application/json", examples = [
					ExampleObject(name = "Unauthorized", value = UNAUTHORIZED_ERROR),
				])],
			),
			ApiResponse(
				responseCode = "404",
				description = "Metrics not found for the authenticated user's day",
				content = [Content(mediaType = "application/json", examples = [
					ExampleObject(name = "Metrics not found", value = METRICS_NOT_FOUND_ERROR),
				])],
			),
		],
	)
	fun getMetrics(
		@PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) date: LocalDate,
	): DailyMetricsResponse = queryService.getMetrics(currentUserId(), date).toResponse()

	@PostMapping("/captures/{captureId}/accept")
	@Operation(
		summary = "Accept an open capture",
		description = "Transitions an owned OPEN capture to ACCEPTED on an OPEN or REOPENED day. " +
			"Accepted captures contribute to metrics when the day is finalized.",
		responses = [
			ApiResponse(
				responseCode = "200",
				description = "Capture accepted and optimistic-lock version incremented",
				content = [Content(mediaType = "application/json", examples = [ExampleObject(name = "Accepted capture", value = ACCEPTED_CAPTURE_EXAMPLE)])],
			),
			ApiResponse(
				responseCode = "401",
				description = "Authentication is missing or invalid",
				content = [Content(mediaType = "application/json", examples = [ExampleObject(name = "Unauthorized", value = UNAUTHORIZED_ERROR)])],
			),
			ApiResponse(
				responseCode = "404",
				description = "Capture not found or owned by another user",
				content = [Content(mediaType = "application/json", examples = [ExampleObject(name = "Capture not found", value = CAPTURE_NOT_FOUND_ERROR)])],
			),
			ApiResponse(
				responseCode = "409",
				description = "Capture is not OPEN or its day is CONFIRMED",
				content = [Content(mediaType = "application/json", examples = [ExampleObject(name = "Capture not open", value = ACCEPT_CONFLICT_ERROR)])],
			),
		],
	)
	fun acceptCapture(@PathVariable captureId: UUID): DailyCaptureResponse =
		confirmationService.accept(currentUserId(), DailyCaptureId(captureId)).toResponse()

	@PostMapping("/captures/{captureId}/reject")
	@Operation(
		summary = "Reject an open capture",
		description = "Transitions an owned OPEN capture to REJECTED on an OPEN or REOPENED day. " +
			"Rejected captures never contribute to finalized metrics.",
		responses = [
			ApiResponse(
				responseCode = "200",
				description = "Capture rejected and optimistic-lock version incremented",
				content = [Content(mediaType = "application/json", examples = [ExampleObject(name = "Rejected capture", value = REJECTED_CAPTURE_EXAMPLE)])],
			),
			ApiResponse(
				responseCode = "401",
				description = "Authentication is missing or invalid",
				content = [Content(mediaType = "application/json", examples = [ExampleObject(name = "Unauthorized", value = UNAUTHORIZED_ERROR)])],
			),
			ApiResponse(
				responseCode = "404",
				description = "Capture not found or owned by another user",
				content = [Content(mediaType = "application/json", examples = [ExampleObject(name = "Capture not found", value = CAPTURE_NOT_FOUND_ERROR)])],
			),
			ApiResponse(
				responseCode = "409",
				description = "Capture is not OPEN or its day is CONFIRMED",
				content = [Content(mediaType = "application/json", examples = [ExampleObject(name = "Capture not open", value = REJECT_CONFLICT_ERROR)])],
			),
		],
	)
	fun rejectCapture(@PathVariable captureId: UUID): DailyCaptureResponse =
		confirmationService.reject(currentUserId(), DailyCaptureId(captureId)).toResponse()

	@PutMapping("/captures/{captureId}")
	@Operation(
		summary = "Atomically replace a complete capture",
		description = "The entries array is the complete new content: omitted entries/items are removed. Existing IDs must " +
			"belong to this capture; absent IDs are generated. The version is an optimistic-lock token. Unchanged items keep " +
			"their snapshot; quantity-only edits recalculate from it. New/changed food references must resolve to an active " +
			"owned food. An existing AI_ESTIMATE may only be preserved unchanged, omitted, or converted to USER_FOOD; " +
			"the client cannot create or modify estimated nutrition.",
		requestBody = io.swagger.v3.oas.annotations.parameters.RequestBody(
			content = [Content(mediaType = "application/json", examples = [ExampleObject(name = "Full content replacement", value = REPLACE_CAPTURE_EXAMPLE)])],
		),
		responses = [
			ApiResponse(
				responseCode = "200",
				description = "Content replaced and version incremented",
				content = [Content(mediaType = "application/json", examples = [
					ExampleObject(name = "Updated calculated capture", value = REPLACE_CAPTURE_RESPONSE_EXAMPLE),
				])],
			),
			ApiResponse(
				responseCode = "400",
				description = "Replacement content or version is invalid",
				content = [Content(mediaType = "application/json", examples = [ExampleObject(name = "Invalid content", value = INVALID_CONTENT_ERROR)])],
			),
			ApiResponse(
				responseCode = "401",
				description = "Authentication is missing or invalid",
				content = [Content(mediaType = "application/json", examples = [ExampleObject(name = "Unauthorized", value = UNAUTHORIZED_ERROR)])],
			),
			ApiResponse(
				responseCode = "404",
				description = "Capture or referenced personal food not found, inactive, or not owned",
				content = [Content(mediaType = "application/json", examples = [ExampleObject(name = "Capture not found", value = CAPTURE_NOT_FOUND_ERROR)])],
			),
			ApiResponse(
				responseCode = "409",
				description = "Stale version or non-editable capture",
				content = [Content(mediaType = "application/json", examples = [ExampleObject(name = "Stale version", value = STALE_VERSION_ERROR)])],
			),
		],
	)
	fun replaceCapture(
		@PathVariable captureId: UUID,
		@Valid @RequestBody request: ReplaceDailyCaptureRequest,
		@RequestAttribute(
			name = RequestCorrelationFilter.REQUEST_ID_ATTRIBUTE,
			required = false,
		) resolvedRequestId: String?,
		@RequestHeader(name = RequestCorrelationFilter.REQUEST_ID_HEADER, required = false) inboundRequestId: String?,
	): DailyCaptureResponse = manualCaptureService.replace(
		userId = currentUserId(),
		captureId = DailyCaptureId(captureId),
		expectedVersion = validated { request.requiredVersion() },
		input = validated { request.toInput() },
		requestId = resolvedRequestId ?: inboundRequestId,
	).toResponse()

	@DeleteMapping("/captures/{captureId}")
	@Operation(
		summary = "Soft delete a capture",
		description = "Transitions an owned capture to SOFT_DELETED on an OPEN or REOPENED day. " +
			"The row and audit history remain stored, while the capture is excluded from metrics.",
		responses = [
			ApiResponse(
				responseCode = "200",
				description = "Capture soft-deleted and optimistic-lock version incremented",
				content = [Content(mediaType = "application/json", examples = [
					ExampleObject(name = "Soft-deleted capture", value = SOFT_DELETED_CAPTURE_EXAMPLE),
				])],
			),
			ApiResponse(
				responseCode = "401",
				description = "Authentication is missing or invalid",
				content = [Content(mediaType = "application/json", examples = [ExampleObject(name = "Unauthorized", value = UNAUTHORIZED_ERROR)])],
			),
			ApiResponse(
				responseCode = "404",
				description = "Capture not found or owned by another user",
				content = [Content(mediaType = "application/json", examples = [ExampleObject(name = "Capture not found", value = CAPTURE_NOT_FOUND_ERROR)])],
			),
			ApiResponse(
				responseCode = "409",
				description = "Capture is already deleted or expired, or its day is CONFIRMED",
				content = [Content(mediaType = "application/json", examples = [ExampleObject(name = "Capture not deletable", value = DELETE_CONFLICT_ERROR)])],
			),
		],
	)
	fun softDeleteCapture(@PathVariable captureId: UUID): DailyCaptureResponse =
		editService.softDelete(currentUserId(), DailyCaptureId(captureId)).toResponse()

	@PostMapping("/days/{date}/finalize")
	@Operation(
		summary = "Finalize a day from accepted captures",
		description = "Requires zero OPEN captures, aggregates only ACCEPTED captures, and transitions an OPEN or REOPENED " +
			"day and its metrics to CONFIRMED. Calling an already CONFIRMED day is idempotent and returns its existing snapshot.",
		responses = [
			ApiResponse(
				responseCode = "200",
				description = "Confirmed metrics snapshot, newly calculated or returned idempotently",
				content = [Content(mediaType = "application/json", examples = [ExampleObject(name = "Confirmed metrics", value = FINALIZED_METRICS_EXAMPLE)])],
			),
			ApiResponse(
				responseCode = "401",
				description = "Authentication is missing or invalid",
				content = [Content(mediaType = "application/json", examples = [ExampleObject(name = "Unauthorized", value = UNAUTHORIZED_ERROR)])],
			),
			ApiResponse(
				responseCode = "404",
				description = "Day not found for the authenticated user",
				content = [Content(mediaType = "application/json", examples = [ExampleObject(name = "Day not found", value = DAY_NOT_FOUND_ERROR)])],
			),
			ApiResponse(
				responseCode = "409",
				description = "At least one capture is still OPEN",
				content = [Content(mediaType = "application/json", examples = [ExampleObject(name = "Open captures", value = OPEN_CAPTURES_ERROR)])],
			),
			ApiResponse(
				responseCode = "500",
				description = "Stored day and metrics state is inconsistent",
				content = [Content(mediaType = "application/json", examples = [ExampleObject(name = "Inconsistent state", value = INCONSISTENT_STATE_ERROR)])],
			),
		],
	)
	fun finalizeDay(
		@PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) date: LocalDate,
	): DailyMetricsResponse = finalizationService.finalizeDay(currentUserId(), date).toResponse()

	@PostMapping("/days/{date}/reopen")
	@Operation(
		summary = "Reopen a confirmed day",
		description = "Marks the day and its existing metrics snapshot as REOPENED. Captures can then be changed; " +
			"the next finalization recalculates and updates the same metrics row. Calling an already REOPENED day is idempotent.",
		responses = [
			ApiResponse(
				responseCode = "200",
				description = "REOPENED day with its REOPENED metrics snapshot",
				content = [Content(mediaType = "application/json", examples = [ExampleObject(name = "Reopened day", value = REOPENED_DAY_EXAMPLE)])],
			),
			ApiResponse(
				responseCode = "401",
				description = "Authentication is missing or invalid",
				content = [Content(mediaType = "application/json", examples = [ExampleObject(name = "Unauthorized", value = UNAUTHORIZED_ERROR)])],
			),
			ApiResponse(
				responseCode = "404",
				description = "Day not found for the authenticated user",
				content = [Content(mediaType = "application/json", examples = [ExampleObject(name = "Day not found", value = DAY_NOT_FOUND_ERROR)])],
			),
			ApiResponse(
				responseCode = "409",
				description = "The day is still in its initial OPEN state",
				content = [Content(mediaType = "application/json", examples = [ExampleObject(name = "Day still open", value = REOPEN_CONFLICT_ERROR)])],
			),
			ApiResponse(
				responseCode = "500",
				description = "The confirmed or reopened metrics snapshot is missing or inconsistent",
				content = [Content(mediaType = "application/json", examples = [ExampleObject(name = "Inconsistent state", value = INCONSISTENT_STATE_ERROR)])],
			),
		],
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
		private const val REPLACE_CAPTURE_RESPONSE_EXAMPLE = """
{
  "captureId": "5a25b934-1681-433b-ac83-f2a2936203ef",
  "dayId": "b3bb97aa-0b12-4f07-a008-e148f2ec7bf0",
  "captureType": "MIXED",
  "status": "OPEN",
  "payload": {
    "schemaVersion": 2,
    "entries": [
      {
        "entryId": "16f39f26-5187-4aab-b7cc-0c0cc94421d2",
        "type": "FOOD",
        "mealType": "DINNER",
        "mealLabel": null,
        "items": [{
          "itemId": "bb8a89aa-17d5-4240-971c-ef529adb8e9a",
          "sourceType": "USER_FOOD",
          "userFoodId": "2db702d6-aeeb-46be-b863-72d552de63ab",
          "displayName": "My usual Greek yogurt",
          "brand": "Example Brand",
          "enteredQuantity": {"amount": 200, "unit": "GRAM"},
          "resolvedQuantity": {"amount": 200, "unit": "GRAM"},
          "nutritionBasisSnapshot": {"amount": 100, "unit": "GRAM"},
          "nutrientsPerBasisSnapshot": {"caloriesKcal": 62, "proteinGrams": 9.5, "carbohydratesGrams": 4.1, "fatGrams": 0.2, "fiberGrams": null, "sugarsGrams": null, "saturatedFatGrams": null, "sodiumMilligrams": null, "saltGrams": null},
          "defaultServingSnapshot": {"amount": 170, "unit": "GRAM"},
          "conversionSnapshot": {"gramsPerPiece": null, "millilitersPerPiece": null, "gramsPerServing": null, "millilitersPerServing": null},
          "calculatedNutrition": {"caloriesKcal": 124, "proteinGrams": 19, "carbohydratesGrams": 8.2, "fatGrams": 0.4, "fiberGrams": null, "sugarsGrams": null, "saturatedFatGrams": null, "sodiumMilligrams": null, "saltGrams": null},
          "nutritionSourceSnapshot": {"type": "USER_FOOD", "originalSourceType": "PRODUCT_LABEL", "estimated": false, "provider": null, "externalId": null, "notes": "Copied manually from the package", "copiedAt": "2026-07-30"},
          "userFoodVersion": 3,
          "userFoodUpdatedAt": "2026-07-31T10:00:00Z"
        }],
        "value": null,
        "unit": null,
        "text": null,
        "nutritionTotal": {"caloriesKcal": 124, "proteinGrams": 19, "carbohydratesGrams": 8.2, "fatGrams": 0.4, "fiberGrams": null, "sugarsGrams": null, "saturatedFatGrams": null, "sodiumMilligrams": null, "saltGrams": null}
      },
      {
        "entryId": "35f2d27e-0649-4e72-a69f-7d85982240ee",
        "type": "WEIGHT",
        "mealType": null,
        "mealLabel": null,
        "items": [],
        "value": 77.8,
        "unit": "KILOGRAM",
        "text": null,
        "nutritionTotal": null
      }
    ]
  },
  "nutritionTotal": {"caloriesKcal": 124, "proteinGrams": 19, "carbohydratesGrams": 8.2, "fatGrams": 0.4, "fiberGrams": null, "sugarsGrams": null, "saturatedFatGrams": null, "sodiumMilligrams": null, "saltGrams": null},
  "createdBy": "USER_UI",
  "updatedBy": "USER_UI",
  "acceptedAt": null,
  "rejectedAt": null,
  "deletedAt": null,
  "createdAt": "2026-07-31T12:00:00Z",
  "updatedAt": "2026-07-31T12:10:00Z",
  "version": 5
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
  "nutritionTotal": {"caloriesKcal": 105.4, "proteinGrams": 16.15, "carbohydratesGrams": 6.97, "fatGrams": 0.34, "fiberGrams": null, "sugarsGrams": null, "saturatedFatGrams": null, "sodiumMilligrams": null, "saltGrams": null},
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
		private const val ACCEPTED_CAPTURE_EXAMPLE = """
{
  "captureId": "5a25b934-1681-433b-ac83-f2a2936203ef",
  "dayId": "b3bb97aa-0b12-4f07-a008-e148f2ec7bf0",
  "captureType": "NOTE",
  "status": "ACCEPTED",
  "payload": {"schemaVersion": 2, "entries": [{"entryId": "55dc12c1-0053-4e0b-87d3-a5d4c89b4f36", "type": "NOTE", "mealType": null, "mealLabel": null, "items": [], "value": null, "unit": null, "text": "Energia buona", "nutritionTotal": null}]},
  "nutritionTotal": null,
  "createdBy": "USER_UI",
  "updatedBy": "USER_UI",
  "acceptedAt": "2026-07-31T12:05:00Z",
  "rejectedAt": null,
  "deletedAt": null,
  "createdAt": "2026-07-31T12:00:00Z",
  "updatedAt": "2026-07-31T12:05:00Z",
  "version": 1
}
"""
		private const val REJECTED_CAPTURE_EXAMPLE = """
{
  "captureId": "5a25b934-1681-433b-ac83-f2a2936203ef",
  "dayId": "b3bb97aa-0b12-4f07-a008-e148f2ec7bf0",
  "captureType": "NOTE",
  "status": "REJECTED",
  "payload": {"schemaVersion": 2, "entries": [{"entryId": "55dc12c1-0053-4e0b-87d3-a5d4c89b4f36", "type": "NOTE", "mealType": null, "mealLabel": null, "items": [], "value": null, "unit": null, "text": "Energia buona", "nutritionTotal": null}]},
  "nutritionTotal": null,
  "createdBy": "USER_UI",
  "updatedBy": "USER_UI",
  "acceptedAt": null,
  "rejectedAt": "2026-07-31T12:05:00Z",
  "deletedAt": null,
  "createdAt": "2026-07-31T12:00:00Z",
  "updatedAt": "2026-07-31T12:05:00Z",
  "version": 1
}
"""
		private const val SOFT_DELETED_CAPTURE_EXAMPLE = """
{
  "captureId": "5a25b934-1681-433b-ac83-f2a2936203ef",
  "dayId": "b3bb97aa-0b12-4f07-a008-e148f2ec7bf0",
  "captureType": "NOTE",
  "status": "SOFT_DELETED",
  "payload": {"schemaVersion": 2, "entries": [{"entryId": "55dc12c1-0053-4e0b-87d3-a5d4c89b4f36", "type": "NOTE", "mealType": null, "mealLabel": null, "items": [], "value": null, "unit": null, "text": "Energia buona", "nutritionTotal": null}]},
  "nutritionTotal": null,
  "createdBy": "USER_UI",
  "updatedBy": "USER_UI",
  "acceptedAt": null,
  "rejectedAt": null,
  "deletedAt": "2026-07-31T12:05:00Z",
  "createdAt": "2026-07-31T12:00:00Z",
  "updatedAt": "2026-07-31T12:05:00Z",
  "version": 1
}
"""
		private const val FINALIZED_METRICS_EXAMPLE = """
{
  "dayId": "b3bb97aa-0b12-4f07-a008-e148f2ec7bf0",
  "dayDate": "2026-07-31",
  "status": "CONFIRMED",
  "bodyWeightKg": 77.8,
  "sleepHours": 7.5,
  "stepsCount": 8400,
  "hydrationLiters": 2.1,
  "caffeineMg": 120,
  "moodLevel": 8,
  "focusLevel": 7,
  "stressLevel": 3,
  "totalCalories": 124,
  "proteinG": 19,
  "carbsG": 8.2,
  "fatG": 0.4,
  "foodLog": [{"mealTempId": "16f39f26-5187-4aab-b7cc-0c0cc94421d2", "mealName": "DINNER", "items": [{"itemTempId": "bb8a89aa-17d5-4240-971c-ef529adb8e9a", "foodName": "My usual Greek yogurt", "quantity": 200, "unit": "g", "calories": 124, "proteinG": 19, "carbsG": 8.2, "fatG": 0.4}]}],
  "dailyNotes": null,
  "generatedFromCaptureIds": ["5a25b934-1681-433b-ac83-f2a2936203ef"],
  "confirmedAt": "2026-07-31T22:00:00Z",
  "recalculatedAt": null
}
"""
		private const val REOPENED_DAY_EXAMPLE = """
{
  "dayId": "b3bb97aa-0b12-4f07-a008-e148f2ec7bf0",
  "dayDate": "2026-07-31",
  "status": "REOPENED",
  "openedAt": "2026-07-31T06:00:00Z",
  "confirmedAt": "2026-07-31T22:00:00Z",
  "reopenedAt": "2026-08-01T08:00:00Z",
  "version": 2,
  "captures": [],
  "metrics": {
    "dayId": "b3bb97aa-0b12-4f07-a008-e148f2ec7bf0",
    "dayDate": "2026-07-31",
    "status": "REOPENED",
    "bodyWeightKg": null,
    "sleepHours": null,
    "stepsCount": null,
    "hydrationLiters": null,
    "caffeineMg": null,
    "moodLevel": null,
    "focusLevel": null,
    "stressLevel": null,
    "totalCalories": null,
    "proteinG": null,
    "carbsG": null,
    "fatG": null,
    "foodLog": [],
    "dailyNotes": null,
    "generatedFromCaptureIds": [],
    "confirmedAt": "2026-07-31T22:00:00Z",
    "recalculatedAt": null
  }
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
		private const val CONFIRMED_DAY_CONFLICT_ERROR = """
{"error":"conflict","message":"Confirmed day cannot receive new captures","fieldErrors":{}}
"""
		private const val METRICS_NOT_FOUND_ERROR = """
{"error":"not_found","message":"Daily metrics were not found for 2026-07-31","fieldErrors":{}}
"""
		private const val INVALID_CONTENT_ERROR = """
{"error":"validation_error","message":"Capture content must contain at least one entry","fieldErrors":{}}
"""
		private const val UNAUTHORIZED_ERROR = """
{"error":"unauthorized"}
"""
		private const val CAPTURE_NOT_FOUND_ERROR = """
{"error":"not_found","message":"Daily capture was not found: 5a25b934-1681-433b-ac83-f2a2936203ef","fieldErrors":{}}
"""
		private const val ACCEPT_CONFLICT_ERROR = """
{"error":"conflict","message":"Only an open capture can be accepted","fieldErrors":{}}
"""
		private const val REJECT_CONFLICT_ERROR = """
{"error":"conflict","message":"Only an open capture can be rejected","fieldErrors":{}}
"""
		private const val DELETE_CONFLICT_ERROR = """
{"error":"conflict","message":"Capture cannot be soft deleted from its current state","fieldErrors":{}}
"""
		private const val DAY_NOT_FOUND_ERROR = """
{"error":"not_found","message":"Daily day was not found for 2026-07-31","fieldErrors":{}}
"""
		private const val OPEN_CAPTURES_ERROR = """
{"error":"conflict","message":"Day cannot be finalized while open captures exist","fieldErrors":{}}
"""
		private const val REOPEN_CONFLICT_ERROR = """
{"error":"conflict","message":"Only a confirmed day can be reopened","fieldErrors":{}}
"""
		private const val INCONSISTENT_STATE_ERROR = """
{"error":"internal_server_error","message":"Daily state is inconsistent","fieldErrors":{}}
"""
	}
}
