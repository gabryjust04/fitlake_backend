package com.fitlake.daily.adapter.rest

/** Compile-time JSON examples kept outside the controller so its API annotations stay readable. */
internal object DailyAiOpenApiExamples {
	const val COMPLETE_REQUEST = """{"text":"Colazione: 40 g di avena e 100 g di mela"}"""
	const val PARTIAL_REQUEST = """{"text":"Ho bevuto 2 litri e preso il solito integratore"}"""
	const val UNRESOLVED_REQUEST = """{"text":"Ho mangiato la solita cosa di ieri"}"""
	const val NO_RELEVANT_REQUEST = """{"text":"Ciao, come stai?"}"""
	const val REPROCESS_REQUEST = """{"text":"Colazione completa: una banana"}"""

	const val COMPLETE_CAPTURE_CREATED = """
{
  "outcome": "CAPTURE_CREATED",
  "replacedCaptureId": null,
  "interpretationOutcome": "COMPLETE",
  "capture": {
    "captureId": "10000000-0000-0000-0000-000000000001",
    "dayId": "20000000-0000-0000-0000-000000000001",
    "date": "2026-07-31",
    "type": "FOOD",
    "status": "OPEN",
    "payload": {
      "schemaVersion": 2,
      "entries": [{
        "entryId": "30000000-0000-0000-0000-000000000001",
        "type": "FOOD",
        "mealType": null,
        "mealLabel": "colazione",
        "items": [
          {
            "itemId": "40000000-0000-0000-0000-000000000001",
            "sourceType": "USER_FOOD",
            "userFoodId": "50000000-0000-0000-0000-000000000001",
            "displayName": "Avena abituale",
            "brand": null,
            "enteredQuantity": {"amount": 40, "unit": "GRAM"},
            "resolvedQuantity": {"amount": 40, "unit": "GRAM"},
            "nutritionBasisSnapshot": {"amount": 100, "unit": "GRAM"},
            "nutrientsPerBasisSnapshot": {
              "caloriesKcal": 370, "proteinGrams": 13, "carbohydratesGrams": 60,
              "fatGrams": 7, "fiberGrams": 10, "sugarsGrams": 1,
              "saturatedFatGrams": 1.2, "sodiumMilligrams": 5, "saltGrams": null
            },
            "defaultServingSnapshot": null,
            "conversionSnapshot": {
              "gramsPerPiece": null, "millilitersPerPiece": null,
              "gramsPerServing": null, "millilitersPerServing": null
            },
            "calculatedNutrition": {
              "caloriesKcal": 148, "proteinGrams": 5.2, "carbohydratesGrams": 24,
              "fatGrams": 2.8, "fiberGrams": 4, "sugarsGrams": 0.4,
              "saturatedFatGrams": 0.48, "sodiumMilligrams": 2, "saltGrams": null
            },
            "nutritionSourceSnapshot": {
              "type": "USER_FOOD", "originalSourceType": "PRODUCT_LABEL", "estimated": false,
              "provider": null, "externalId": null, "notes": null, "copiedAt": null
            },
            "userFoodVersion": 3,
            "userFoodUpdatedAt": "2026-07-20T08:00:00Z"
          },
          {
            "itemId": "40000000-0000-0000-0000-000000000002",
            "sourceType": "AI_ESTIMATE",
            "userFoodId": null,
            "displayName": "mela",
            "brand": null,
            "enteredQuantity": {"amount": 100, "unit": "GRAM"},
            "resolvedQuantity": {"amount": 100, "unit": "GRAM"},
            "nutritionBasisSnapshot": null,
            "nutrientsPerBasisSnapshot": null,
            "defaultServingSnapshot": null,
            "conversionSnapshot": null,
            "calculatedNutrition": {
              "caloriesKcal": 52, "proteinGrams": 0.3, "carbohydratesGrams": 14,
              "fatGrams": 0.2, "fiberGrams": 2.4, "sugarsGrams": 10.4,
              "saturatedFatGrams": 0.03, "sodiumMilligrams": 1, "saltGrams": null
            },
            "nutritionSourceSnapshot": null,
            "userFoodVersion": null,
            "userFoodUpdatedAt": null
          }
        ],
        "value": null,
        "unit": null,
        "text": null,
        "nutritionTotal": {
          "caloriesKcal": 200, "proteinGrams": 5.5, "carbohydratesGrams": 38,
          "fatGrams": 3, "fiberGrams": 6.4, "sugarsGrams": 10.8,
          "saturatedFatGrams": 0.51, "sodiumMilligrams": 3, "saltGrams": null
        }
      }]
    },
    "createdBy": "AI",
    "createdAt": "2026-07-31T08:00:00Z",
    "version": 0
  },
  "reason": null
}
"""

	const val PARTIAL_CAPTURE_CREATED = """
{
  "outcome": "CAPTURE_CREATED",
  "replacedCaptureId": null,
  "interpretationOutcome": "PARTIAL",
  "capture": {
    "captureId": "10000000-0000-0000-0000-000000000002",
    "dayId": "20000000-0000-0000-0000-000000000001",
    "date": "2026-07-31",
    "type": "DAILY_FIELDS",
    "status": "OPEN",
    "payload": {
      "schemaVersion": 2,
      "entries": [
        {
          "entryId": "30000000-0000-0000-0000-000000000002", "type": "HYDRATION",
          "mealType": null, "mealLabel": null, "items": [], "value": 2, "unit": "LITER",
          "text": null, "nutritionTotal": null
        },
        {
          "entryId": "30000000-0000-0000-0000-000000000003", "type": "NOTE",
          "mealType": null, "mealLabel": null, "items": [], "value": null, "unit": null,
          "text": "preso il solito integratore", "nutritionTotal": null
        }
      ]
    },
    "createdBy": "AI",
    "createdAt": "2026-07-31T08:05:00Z",
    "version": 0
  },
  "reason": null
}
"""

	const val UNRESOLVED_CAPTURE_CREATED = """
{
  "outcome": "CAPTURE_CREATED",
  "replacedCaptureId": null,
  "interpretationOutcome": "UNRESOLVED",
  "capture": {
    "captureId": "10000000-0000-0000-0000-000000000003",
    "dayId": "20000000-0000-0000-0000-000000000001",
    "date": "2026-07-31",
    "type": "NOTE",
    "status": "OPEN",
    "payload": {
      "schemaVersion": 2,
      "entries": [{
        "entryId": "30000000-0000-0000-0000-000000000004", "type": "NOTE",
        "mealType": null, "mealLabel": null, "items": [], "value": null, "unit": null,
        "text": "Ho mangiato la solita cosa di ieri", "nutritionTotal": null
      }]
    },
    "createdBy": "AI",
    "createdAt": "2026-07-31T08:10:00Z",
    "version": 0
  },
  "reason": null
}
"""

	const val CAPTURE_REPLACED = """
{
  "outcome": "CAPTURE_REPLACED",
  "replacedCaptureId": "10000000-0000-0000-0000-000000000001",
  "interpretationOutcome": "COMPLETE",
  "capture": {
    "captureId": "10000000-0000-0000-0000-000000000004",
    "dayId": "20000000-0000-0000-0000-000000000001",
    "date": "2026-07-31",
    "type": "FOOD",
    "status": "OPEN",
    "payload": {
      "schemaVersion": 2,
      "entries": [{
        "entryId": "30000000-0000-0000-0000-000000000005",
        "type": "FOOD",
        "mealType": null,
        "mealLabel": "colazione",
        "items": [{
          "itemId": "40000000-0000-0000-0000-000000000003",
          "sourceType": "AI_ESTIMATE",
          "userFoodId": null,
          "displayName": "banana",
          "brand": null,
          "enteredQuantity": {"amount": 1, "unit": "PIECE"},
          "resolvedQuantity": {"amount": 1, "unit": "PIECE"},
          "nutritionBasisSnapshot": null,
          "nutrientsPerBasisSnapshot": null,
          "defaultServingSnapshot": null,
          "conversionSnapshot": null,
          "calculatedNutrition": {
            "caloriesKcal": 105, "proteinGrams": 1.3, "carbohydratesGrams": 27,
            "fatGrams": 0.4, "fiberGrams": 3.1, "sugarsGrams": 14.4,
            "saturatedFatGrams": 0.1, "sodiumMilligrams": 1, "saltGrams": null
          },
          "nutritionSourceSnapshot": null,
          "userFoodVersion": null,
          "userFoodUpdatedAt": null
        }],
        "value": null,
        "unit": null,
        "text": null,
        "nutritionTotal": {
          "caloriesKcal": 105, "proteinGrams": 1.3, "carbohydratesGrams": 27,
          "fatGrams": 0.4, "fiberGrams": 3.1, "sugarsGrams": 14.4,
          "saturatedFatGrams": 0.1, "sodiumMilligrams": 1, "saltGrams": null
        }
      }]
    },
    "createdBy": "AI",
    "createdAt": "2026-07-31T08:15:00Z",
    "version": 0
  },
  "reason": null
}
"""

	const val NO_RELEVANT_DATA = """
{
  "outcome": "NO_RELEVANT_DATA",
  "replacedCaptureId": null,
  "interpretationOutcome": "NO_RELEVANT_DATA",
  "capture": null,
  "reason": "The message contains no relevant Daily data"
}
"""

	const val BLANK_TEXT_ERROR = """{"error":"validation_error","message":"Request validation failed","fieldErrors":{"text":"text must not be blank"}}"""
	const val INVALID_PARAMETER_ERROR = """{"error":"invalid_request","message":"Request parameters are missing or invalid","fieldErrors":{}}"""
	const val UNAUTHORIZED_ERROR = """{"error":"unauthorized"}"""
	const val IDEMPOTENCY_CONFLICT_ERROR = """{"error":"idempotency_key_conflict","message":"The idempotency key was already used for a different request","fieldErrors":{}}"""
	const val OPERATION_IN_PROGRESS_ERROR = """{"error":"ai_operation_in_progress","message":"An operation with this idempotency key is already being processed","fieldErrors":{}}"""
	const val INVALID_OUTPUT_ERROR = """{"error":"ai_invalid_output","message":"The AI provider returned an invalid structured result","fieldErrors":{}}"""
	const val NOT_CONFIGURED_ERROR = """{"error":"ai_not_configured","message":"Daily AI is not configured","fieldErrors":{}}"""
	const val PROVIDER_UNAVAILABLE_ERROR = """{"error":"ai_provider_unavailable","message":"The AI provider is unavailable","fieldErrors":{}}"""
	const val TIMEOUT_ERROR = """{"error":"ai_timeout","message":"The AI provider timed out","fieldErrors":{}}"""
	const val INTERNAL_ERROR = """{"error":"internal_server_error","message":"Daily AI processing failed","fieldErrors":{}}"""
	const val CAPTURE_NOT_FOUND_ERROR = """{"error":"not_found","message":"Daily capture was not found: 10000000-0000-0000-0000-000000000001","fieldErrors":{}}"""
	const val NOT_REPROCESSABLE_ERROR = """{"error":"conflict","message":"Only an open capture can be reprocessed","fieldErrors":{}}"""
}
