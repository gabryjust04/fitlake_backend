package com.fitlake.daily.application.ai

import com.fitlake.daily.domain.capture.DailyCaptureId
import com.fitlake.daily.domain.inbox.DailyInboxEventId
import com.fitlake.user.domain.UserId
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

data class DailyAiProviderMetadata(
	val provider: String,
	val model: String,
	val promptVersion: String,
)

data class DailyAiRequestContext(
	val inboxEventId: DailyInboxEventId,
	val userId: UserId,
	val date: LocalDate,
	val timezone: ZoneId,
	val replacesCaptureId: DailyCaptureId?,
	val metadata: DailyAiProviderMetadata,
	val startedAt: Instant,
	val processingAttemptId: UUID,
)

data class InterpretDailyMessageRequest(
	val targetDate: LocalDate,
	val timezone: ZoneId,
	val text: String,
)

data class InterpretedDailyMessage(
	val interpretation: DailyMessageInterpretation,
	val retryCount: Int = 0,
	val inputTokens: Long? = null,
	val outputTokens: Long? = null,
) {
	init {
		require(retryCount >= 0) { "AI retry count must not be negative" }
		require(inputTokens == null || inputTokens >= 0) { "AI input token count must not be negative" }
		require(outputTokens == null || outputTokens >= 0) { "AI output token count must not be negative" }
	}
}

fun interface CaptureInterpreterPort {
	fun interpret(request: InterpretDailyMessageRequest): InterpretedDailyMessage

	val metadata: DailyAiProviderMetadata
		get() = DailyAiProviderMetadata("UNKNOWN", "unknown", "unknown")
}

enum class DailyMessageInterpretationOutcome {
	COMPLETE,
	PARTIAL,
	UNRESOLVED,
	NO_RELEVANT_DATA,
}

data class DailyMessageInterpretation(
	val outcome: DailyMessageInterpretationOutcome,
	val meals: List<AiMealInterpretation> = emptyList(),
	val fields: List<AiDailyFieldInterpretation> = emptyList(),
	val unresolvedFragments: List<String> = emptyList(),
	val confidence: BigDecimal? = null,
)

data class AiMealInterpretation(
	val mealName: String? = null,
	val items: List<AiFoodInterpretation> = emptyList(),
)

data class AiFoodInterpretation(
	val originalFragment: String,
	val searchText: String,
	val statedQuantity: AiFoodQuantity? = null,
	val estimatedQuantity: AiFoodQuantity,
	val nutritionEstimate: AiNutritionEstimate,
	val assumptions: List<String> = emptyList(),
)

data class AiFoodQuantity(
	val amount: BigDecimal,
	val unit: String,
)

data class AiNutritionEstimate(
	val basis: AiFoodQuantity,
	val caloriesKcal: BigDecimal,
	val proteinGrams: BigDecimal,
	val carbohydratesGrams: BigDecimal,
	val fatGrams: BigDecimal,
	val fiberGrams: BigDecimal? = null,
	val sugarsGrams: BigDecimal? = null,
	val saturatedFatGrams: BigDecimal? = null,
	val sodiumMilligrams: BigDecimal? = null,
	val saltGrams: BigDecimal? = null,
)

enum class AiDailyFieldType {
	BODY_WEIGHT_KG,
	SLEEP_HOURS,
	STEPS_COUNT,
	HYDRATION_LITERS,
	CAFFEINE_MG,
	MOOD_LEVEL,
	FOCUS_LEVEL,
	STRESS_LEVEL,
	DAILY_NOTES,
}

data class AiDailyFieldInterpretation(
	val field: AiDailyFieldType,
	val numericValue: BigDecimal? = null,
	val textValue: String? = null,
	val originalFragment: String,
)

sealed interface DailyAiPreparation {
	data class Execute(
		val context: DailyAiRequestContext,
		val persistedRawText: String,
	) : DailyAiPreparation {
		init {
			require(persistedRawText.isNotBlank()) { "Persisted Daily AI input must not be blank" }
		}
	}
	data class Replay(val result: DailyAiResult) : DailyAiPreparation
}

sealed interface DailyAiResult {
	val date: LocalDate

	data class CaptureCreated(
		override val date: LocalDate,
		val capture: DailyAiCaptureResult,
		val replacedCaptureId: DailyCaptureId?,
		val interpretationOutcome: DailyMessageInterpretationOutcome,
	) : DailyAiResult

	data class NoRelevantData(
		override val date: LocalDate,
		val reason: String = "The message contains no relevant Daily data",
	) : DailyAiResult
}
