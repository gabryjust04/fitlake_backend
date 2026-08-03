package com.fitlake.daily.infrastructure.ai

import com.fitlake.daily.application.ai.AiDailyFieldInterpretation
import com.fitlake.daily.application.ai.AiDailyFieldType
import com.fitlake.daily.application.ai.AiFoodInterpretation
import com.fitlake.daily.application.ai.AiFoodQuantity
import com.fitlake.daily.application.ai.AiNutritionEstimate
import com.fitlake.daily.application.ai.CaptureInterpreterPort
import com.fitlake.daily.application.ai.DailyAiException
import com.fitlake.daily.application.ai.DailyAiInvalidOutputException
import com.fitlake.daily.application.ai.DailyAiProviderMetadata
import com.fitlake.daily.application.ai.DailyAiProviderAuthenticationException
import com.fitlake.daily.application.ai.DailyAiProviderQuotaException
import com.fitlake.daily.application.ai.DailyAiProviderUnavailableException
import com.fitlake.daily.application.ai.DailyAiRateLimitException
import com.fitlake.daily.application.ai.DailyAiTimeoutException
import com.fitlake.daily.application.ai.DailyMessageInterpretation
import com.fitlake.daily.application.ai.DailyMessageInterpretationOutcome
import com.fitlake.daily.application.ai.InterpretDailyMessageRequest
import com.fitlake.daily.application.ai.InterpretedDailyMessage
import org.slf4j.LoggerFactory
import org.springframework.ai.chat.messages.SystemMessage
import org.springframework.ai.chat.messages.UserMessage
import org.springframework.ai.chat.model.ChatModel
import org.springframework.ai.chat.model.ChatResponse
import org.springframework.ai.chat.prompt.Prompt
import org.springframework.ai.converter.BeanOutputConverter
import org.springframework.ai.openai.OpenAiChatModel
import org.springframework.ai.openai.OpenAiChatOptions
import org.springframework.ai.util.JacksonUtils
import org.springframework.ai.util.JsonHelper
import tools.jackson.databind.DeserializationFeature
import tools.jackson.databind.json.JsonMapper
import java.math.BigDecimal
import com.openai.errors.OpenAIServiceException
import java.net.SocketTimeoutException
import java.net.http.HttpTimeoutException
import java.util.concurrent.TimeoutException

internal const val DAILY_AI_PROMPT_VERSION = "daily-capture-v3"
internal const val DAILY_AI_PROMPT_RESOURCE = "classpath:prompts/daily-capture-v3.txt"
internal const val DEFAULT_DAILY_AI_MAX_OUTPUT_TOKENS = 4096
internal const val DEFAULT_DAILY_AI_MAX_CORRECTION_RETRIES = 1
internal const val MAX_DAILY_AI_MAX_CORRECTION_RETRIES = 3

/**
 * Pure OpenAI-compatible structured-output adapter.
 *
 * It calls the provider and validates the returned interpretation only. It deliberately has no
 * repository, catalog, application-terminal, or tool-callback dependency.
 */
internal class SpringAiDailyAiInterpreter(
	private val chatModel: ChatModel,
	private val systemPrompt: String,
	override val metadata: DailyAiProviderMetadata,
	private val maxOutputTokens: Int = DEFAULT_DAILY_AI_MAX_OUTPUT_TOKENS,
	private val maxCorrectionRetries: Int = DEFAULT_DAILY_AI_MAX_CORRECTION_RETRIES,
	private val nativeStructuredOutputEnabled: Boolean = true,
) : CaptureInterpreterPort {
	private val jsonHelper = JsonHelper()
	private val outputConverter = BeanOutputConverter(
		DailyMessageInterpretation::class.java,
		JsonMapper.builder()
			.addModules(JacksonUtils.instantiateAvailableModules())
			.enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
			.build(),
	)
	private val responseFormat = OpenAiChatModel.ResponseFormat.builder()
		.type(OpenAiChatModel.ResponseFormat.Type.JSON_SCHEMA)
		.jsonSchema(outputConverter.jsonSchema)
		.build()

	init {
		require(maxOutputTokens > 0) { "Daily AI max output tokens must be greater than zero" }
		require(maxCorrectionRetries in 0..MAX_DAILY_AI_MAX_CORRECTION_RETRIES) {
			"Daily AI correction retries must be between 0 and $MAX_DAILY_AI_MAX_CORRECTION_RETRIES"
		}
	}

	override fun interpret(request: InterpretDailyMessageRequest): InterpretedDailyMessage {
		if (request.text.isBlank()) {
			throw DailyAiInvalidOutputException()
		}

		val tokenUsage = AccumulatedTokenUsage()
		for (attempt in 0..maxCorrectionRetries) {
			val response = callModel(prompt(request, correctionAttempt = attempt > 0))
			tokenUsage.add(response)
			try {
				val interpretation = parseAndValidate(response, request)
				val builder = logger.atDebug()
					.addKeyValue("event", "daily_ai_provider_response_validated")
					.addKeyValue("outcome", "success")
					.addKeyValue("provider", metadata.provider)
					.addKeyValue("model", metadata.model)
					.addKeyValue("promptVersion", metadata.promptVersion)
					.addKeyValue("interpretationStatus", interpretation.outcome)
					.addKeyValue("retryCount", attempt)
				tokenUsage.inputTokens?.let { builder.addKeyValue("inputTokenCount", it) }
				tokenUsage.outputTokens?.let { builder.addKeyValue("outputTokenCount", it) }
				builder.log("Daily AI provider response validated")
				return InterpretedDailyMessage(
					interpretation = interpretation,
					retryCount = attempt,
					inputTokens = tokenUsage.inputTokens,
					outputTokens = tokenUsage.outputTokens,
				)
			} catch (exception: InvalidStructuredOutputException) {
				val retrying = attempt < maxCorrectionRetries
				if (retrying) {
					logger.atWarn()
						.addKeyValue("event", "daily_ai_schema_retry")
						.addKeyValue("outcome", "retry")
						.addKeyValue("errorCode", "AI_OUTPUT_SCHEMA_INVALID")
						.addKeyValue("provider", metadata.provider)
						.addKeyValue("model", metadata.model)
						.addKeyValue("promptVersion", metadata.promptVersion)
						.addKeyValue("attempt", attempt + 1)
						.addKeyValue(
							"reasonType",
							exception.cause?.javaClass?.simpleName ?: exception.javaClass.simpleName,
						)
						.log("Daily AI structured output will be retried")
				}
				if (!retrying) break
			}
		}

		// Do not retain parser/provider output in the externally propagated cause chain.
		throw DailyAiInvalidOutputException()
	}

	private fun prompt(request: InterpretDailyMessageRequest, correctionAttempt: Boolean): Prompt {
		val correctionInstruction = if (correctionAttempt) "\n\n$CORRECTION_INSTRUCTION" else ""
		val structuredPrompt = "$systemPrompt\n\n${outputConverter.format}$correctionInstruction"
		val options = OpenAiChatOptions.builder()
			.temperature(0.0)
			.maxTokens(maxOutputTokens)
		if (nativeStructuredOutputEnabled) {
			options.responseFormat(responseFormat)
		}
		return Prompt(
			listOf(
				SystemMessage(structuredPrompt),
				UserMessage(userEnvelope(request)),
			),
			options.build(),
		)
	}

	private fun userEnvelope(request: InterpretDailyMessageRequest): String = jsonHelper.toJson(
		linkedMapOf(
			"targetDate" to request.targetDate.toString(),
			"timezone" to request.timezone.id,
			"text" to request.text,
		),
	)

	private fun parseAndValidate(
		response: ChatResponse,
		request: InterpretDailyMessageRequest,
	): DailyMessageInterpretation = try {
		require(response.results.size == 1) { "Structured response requires exactly one generation" }
		val output = response.results.single().output
		require(output.toolCalls.isEmpty()) { "Structured response cannot contain tool calls" }
		val responseText = output.text?.trim().orEmpty()
		require(responseText.startsWith('{') && responseText.endsWith('}')) {
			"Structured response must contain exactly one JSON object"
		}
		val interpretation = requireNotNull(outputConverter.convert(responseText)) {
			"Structured response could not be converted"
		}
		validateInterpretation(interpretation, request.text)
		interpretation
	} catch (exception: RuntimeException) {
		throw InvalidStructuredOutputException(exception)
	}

	private fun validateInterpretation(interpretation: DailyMessageInterpretation, rawText: String) {
		interpretation.confidence?.let { confidence ->
			require(confidence in BigDecimal.ZERO..BigDecimal.ONE) { "Confidence is outside the allowed range" }
		}
		require(interpretation.meals.size <= MAX_CAPTURE_ENTRIES) { "Too many interpreted meals" }
		require(interpretation.fields.size <= MAX_CAPTURE_ENTRIES) { "Too many interpreted fields" }
		require(interpretation.fields.map(AiDailyFieldInterpretation::field).distinct().size == interpretation.fields.size) {
			"Daily fields must be unique"
		}

		interpretation.meals.forEach { meal ->
			require(meal.mealName == null || meal.mealName.isNotBlank()) { "Meal name must be null or non-blank" }
			require(meal.mealName == null || meal.mealName.length <= MAX_MEAL_NAME_LENGTH) { "Meal name is too long" }
			require(meal.items.isNotEmpty()) { "An interpreted meal requires food items" }
			require(meal.items.size <= MAX_FOOD_ITEMS_PER_MEAL) { "Too many interpreted food items" }
			meal.items.forEach { food -> validateFood(food, rawText) }
		}
		interpretation.fields.forEach { field -> validateField(field, rawText) }

		val unresolved = interpretation.unresolvedFragments
		require(unresolved.size <= MAX_UNRESOLVED_FRAGMENTS) { "Too many unresolved fragments" }
		require(unresolved.distinct().size == unresolved.size) { "Unresolved fragments must be unique" }
		unresolved.forEach { fragment -> requireExactFragment(fragment, rawText, "Unresolved fragment") }

		val structuredFactCount = interpretation.meals.sumOf { it.items.size } + interpretation.fields.size
		when (interpretation.outcome) {
			DailyMessageInterpretationOutcome.COMPLETE -> {
				require(structuredFactCount > 0) { "COMPLETE requires structured Daily data" }
				require(unresolved.isEmpty()) { "COMPLETE cannot contain unresolved fragments" }
			}
			DailyMessageInterpretationOutcome.PARTIAL -> {
				require(structuredFactCount > 0) { "PARTIAL requires structured Daily data" }
				require(unresolved.isNotEmpty()) { "PARTIAL requires unresolved fragments" }
			}
			DailyMessageInterpretationOutcome.UNRESOLVED -> {
				require(structuredFactCount == 0) { "UNRESOLVED cannot contain structured Daily data" }
				require(unresolved.isEmpty() || unresolved == listOf(rawText)) {
					"UNRESOLVED must preserve the complete original message"
				}
			}
			DailyMessageInterpretationOutcome.NO_RELEVANT_DATA -> {
				require(structuredFactCount == 0) { "NO_RELEVANT_DATA cannot contain structured Daily data" }
				require(unresolved.isEmpty()) { "NO_RELEVANT_DATA cannot contain unresolved fragments" }
			}
		}

		val resultingEntryCount = interpretation.meals.size + interpretation.fields.size +
			if (interpretation.outcome == DailyMessageInterpretationOutcome.PARTIAL) unresolved.size else
				if (interpretation.outcome == DailyMessageInterpretationOutcome.UNRESOLVED) 1 else 0
		require(resultingEntryCount in 0..MAX_CAPTURE_ENTRIES) { "Interpretation would create too many entries" }
	}

	private fun validateFood(food: AiFoodInterpretation, rawText: String) {
		requireExactFragment(food.originalFragment, rawText, "Food original fragment")
		require(food.searchText.isNotBlank()) { "Food search text is required" }
		require(food.searchText.length <= MAX_FOOD_SEARCH_TEXT_LENGTH) { "Food search text is too long" }
		food.statedQuantity?.let(::validateQuantity)
		validateQuantity(food.estimatedQuantity)
		validateNutrition(food.nutritionEstimate)
		validateEstimateScaling(food)
		require(food.assumptions.size <= MAX_ASSUMPTIONS) { "Too many food assumptions" }
		food.assumptions.forEach { assumption ->
			require(assumption.isNotBlank() && assumption.length <= MAX_ASSUMPTION_LENGTH) {
				"Food assumptions must be concise and non-blank"
			}
		}
	}

	private fun validateQuantity(quantity: AiFoodQuantity) {
		requirePositiveDecimal(quantity.amount, "Food quantity")
		require(quantity.unit in CANONICAL_FOOD_UNITS) { "Food quantity unit is unsupported" }
	}

	private fun validateNutrition(estimate: AiNutritionEstimate) {
		validateQuantity(estimate.basis)
		requireNonNegativeDecimal(estimate.caloriesKcal, "Estimated calories")
		require(estimate.caloriesKcal <= MAX_AI_CALORIES) { "Estimated calories are outside the allowed range" }
		listOf(estimate.proteinGrams, estimate.carbohydratesGrams, estimate.fatGrams).forEach { macro ->
			requireNonNegativeDecimal(macro, "Estimated macronutrient")
			require(macro <= MAX_AI_MACRO_GRAMS) { "Estimated macronutrient is outside the allowed range" }
		}
		listOf(
			estimate.fiberGrams,
			estimate.sugarsGrams,
			estimate.saturatedFatGrams,
			estimate.sodiumMilligrams,
			estimate.saltGrams,
		).forEach { optional -> optional?.let { requireNonNegativeDecimal(it, "Estimated optional nutrient") } }
	}

	private fun validateEstimateScaling(food: AiFoodInterpretation) {
		val quantityDimension = food.estimatedQuantity.unit.foodDimension()
		val basisDimension = food.nutritionEstimate.basis.unit.foodDimension()
		require(quantityDimension == basisDimension) {
			"Estimated quantity and nutrition basis must be deterministically scalable"
		}
	}

	private fun validateField(field: AiDailyFieldInterpretation, rawText: String) {
		requireExactFragment(field.originalFragment, rawText, "Daily field original fragment")
		when (field.field) {
			AiDailyFieldType.DAILY_NOTES -> {
				require(field.numericValue == null) { "DAILY_NOTES cannot contain a numeric value" }
				val textValue = requireNotNull(field.textValue).takeIf(String::isNotBlank)
					?: throw IllegalArgumentException("DAILY_NOTES requires text")
				require(textValue.length <= MAX_NOTE_LENGTH) { "Daily notes are too long" }
				requireExactFragment(textValue, rawText, "Daily notes text")
			}
			else -> {
				require(field.textValue == null) { "Numeric Daily field cannot contain text" }
				val value = requireNotNull(field.numericValue) { "Numeric Daily field requires a value" }
				requireNonNegativeDecimal(value, "Daily field value")
				when (field.field) {
					AiDailyFieldType.BODY_WEIGHT_KG -> require(value.isBetween("1", "500")) { "Weight is outside the allowed range" }
					AiDailyFieldType.SLEEP_HOURS -> require(value.isBetween("0", "24")) { "Sleep is outside the allowed range" }
					AiDailyFieldType.STEPS_COUNT -> require(value.isIntegerIn(0, 200_000)) { "Steps are outside the allowed range" }
					AiDailyFieldType.HYDRATION_LITERS -> require(value.isBetween("0", "20")) { "Hydration is outside the allowed range" }
					AiDailyFieldType.CAFFEINE_MG -> require(value.isIntegerIn(0, 5_000)) { "Caffeine is outside the allowed range" }
					AiDailyFieldType.MOOD_LEVEL,
					AiDailyFieldType.FOCUS_LEVEL,
					AiDailyFieldType.STRESS_LEVEL,
					-> require(value.isIntegerIn(1, 10)) { "Level is outside the allowed range" }
					AiDailyFieldType.DAILY_NOTES -> error("Handled above")
				}
			}
		}
	}

	private fun requireExactFragment(fragment: String, rawText: String, label: String) {
		require(fragment.isNotBlank()) { "$label must not be blank" }
		require(fragment.length <= rawText.length && rawText.contains(fragment)) {
			"$label must be copied exactly from the input"
		}
	}

	private fun requirePositiveDecimal(value: BigDecimal, label: String) {
		require(value > BigDecimal.ZERO) { "$label must be positive" }
		requireDecimalBounds(value, label)
	}

	private fun requireNonNegativeDecimal(value: BigDecimal, label: String) {
		require(value >= BigDecimal.ZERO) { "$label must not be negative" }
		requireDecimalBounds(value, label)
	}

	private fun requireDecimalBounds(value: BigDecimal, label: String) {
		require(value <= MAX_DECIMAL_VALUE) { "$label is too large" }
		require(value.stripTrailingZeros().scale().coerceAtLeast(0) <= MAX_DECIMAL_SCALE) {
			"$label has too many decimal places"
		}
	}

	private fun String.foodDimension(): FoodDimension = when (this) {
		"g", "kg" -> FoodDimension.MASS
		"ml", "l" -> FoodDimension.VOLUME
		"unit" -> FoodDimension.PIECE
		"portion" -> FoodDimension.SERVING
		else -> error("Unsupported canonical food unit")
	}

	private fun BigDecimal.isBetween(minimum: String, maximum: String): Boolean =
		this >= minimum.toBigDecimal() && this <= maximum.toBigDecimal()

	private fun BigDecimal.isIntegerIn(minimum: Int, maximum: Int): Boolean =
		stripTrailingZeros().scale() <= 0 && this >= minimum.toBigDecimal() && this <= maximum.toBigDecimal()

	private fun callModel(prompt: Prompt): ChatResponse = try {
		chatModel.call(prompt)
	} catch (exception: DailyAiException) {
		throw exception
	} catch (exception: RuntimeException) {
		if (exception.hasTimeoutCause()) {
			throw DailyAiTimeoutException(exception)
		}
		throw when (exception.openAiStatusCode()) {
			401, 403 -> DailyAiProviderAuthenticationException(exception)
			402 -> DailyAiProviderQuotaException(exception)
			429 -> DailyAiRateLimitException(exception)
			408, 504 -> DailyAiTimeoutException(exception)
			else -> DailyAiProviderUnavailableException(exception)
		}
	}

	private fun Throwable.openAiStatusCode(): Int? {
		val visited = mutableSetOf<Throwable>()
		var current: Throwable? = this
		while (current != null && visited.add(current)) {
			if (current is OpenAIServiceException) return current.statusCode()
			current = current.cause
		}
		return null
	}

	private fun Throwable.hasTimeoutCause(): Boolean {
		val visited = mutableSetOf<Throwable>()
		var current: Throwable? = this
		while (current != null && visited.add(current)) {
			if (
				current is SocketTimeoutException ||
				current is HttpTimeoutException ||
				current is TimeoutException ||
				current is java.io.InterruptedIOException && current.message?.contains("timeout", ignoreCase = true) == true
			) {
				return true
			}
			current = current.cause
		}
		return false
	}

	private class InvalidStructuredOutputException(cause: RuntimeException) : RuntimeException(cause)

	private enum class FoodDimension {
		MASS,
		VOLUME,
		PIECE,
		SERVING,
	}

	private class AccumulatedTokenUsage {
		var inputTokens: Long? = null
			private set
		var outputTokens: Long? = null
			private set

		fun add(response: ChatResponse) {
			response.metadata.usage.promptTokens.takeIf { it > 0 }?.toLong()?.let { tokens ->
				inputTokens = (inputTokens ?: 0L) + tokens
			}
			response.metadata.usage.completionTokens.takeIf { it > 0 }?.toLong()?.let { tokens ->
				outputTokens = (outputTokens ?: 0L) + tokens
			}
		}
	}

	private companion object {
		const val CORRECTION_INSTRUCTION =
			"The previous response failed schema or semantic validation. Return a corrected JSON object only. " +
				"Re-read the original text and obey every schema, outcome, quantity, and exact-fragment rule."
		const val MAX_CAPTURE_ENTRIES = 50
		const val MAX_FOOD_ITEMS_PER_MEAL = 200
		const val MAX_MEAL_NAME_LENGTH = 100
		const val MAX_FOOD_SEARCH_TEXT_LENGTH = 255
		const val MAX_UNRESOLVED_FRAGMENTS = 50
		const val MAX_ASSUMPTIONS = 20
		const val MAX_ASSUMPTION_LENGTH = 500
		const val MAX_NOTE_LENGTH = 10_000
		const val MAX_DECIMAL_SCALE = 6
		val MAX_DECIMAL_VALUE: BigDecimal = BigDecimal("1000000000000")
		val MAX_AI_CALORIES: BigDecimal = BigDecimal("100000")
		val MAX_AI_MACRO_GRAMS: BigDecimal = BigDecimal("5000")
		val CANONICAL_FOOD_UNITS = setOf("g", "kg", "ml", "l", "unit", "portion")
		val logger = LoggerFactory.getLogger(SpringAiDailyAiInterpreter::class.java)
	}
}
