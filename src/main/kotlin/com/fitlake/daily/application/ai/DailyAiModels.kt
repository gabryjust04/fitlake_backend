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

sealed interface DailyAiPreparation {
	data class Execute(val context: DailyAiRequestContext) : DailyAiPreparation
	data class Replay(val result: DailyAiResult) : DailyAiPreparation
}

sealed interface DailyAiResult {
	val date: LocalDate

	data class CaptureCreated(
		override val date: LocalDate,
		val capture: DailyAiCaptureResult,
		val replacedCaptureId: DailyCaptureId?,
	) : DailyAiResult

	data class ClarificationRequired(
		override val date: LocalDate,
		val question: String,
	) : DailyAiResult

	data class NoOp(
		override val date: LocalDate,
		val reason: String,
	) : DailyAiResult
}

interface DailyAiInterpreter {
	val metadata: DailyAiProviderMetadata

	fun interpret(context: DailyAiRequestContext, text: String): DailyAiResult
}

data class AiCaptureProposal(
	val type: String? = null,
	val meals: List<AiMealProposal> = emptyList(),
	val fields: AiDailyFieldsProposal = AiDailyFieldsProposal(),
	val note: String? = null,
	val confidence: BigDecimal? = null,
)

data class AiMealProposal(
	val mealName: String? = null,
	val items: List<AiFoodItemProposal> = emptyList(),
)

data class AiFoodItemProposal(
	val foodName: String? = null,
	val quantity: BigDecimal? = null,
	val unit: String? = null,
	val calories: BigDecimal,
	val proteinG: BigDecimal,
	val carbsG: BigDecimal,
	val fatG: BigDecimal,
)

data class AiDailyFieldsProposal(
	val bodyWeightKg: BigDecimal? = null,
	val sleepHours: BigDecimal? = null,
	val stepsCount: Int? = null,
	val hydrationLiters: BigDecimal? = null,
	val caffeineMg: Int? = null,
	val moodLevel: Int? = null,
	val focusLevel: Int? = null,
	val stressLevel: Int? = null,
	val dailyNotes: String? = null,
)

data class AiClarificationProposal(
	val question: String? = null,
)

data class AiNoOpProposal(
	val reason: String? = null,
)
