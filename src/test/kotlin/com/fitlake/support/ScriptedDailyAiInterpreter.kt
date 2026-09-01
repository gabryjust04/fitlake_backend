package com.fitlake.support

import com.fitlake.daily.application.ai.CaptureInterpreterPort
import com.fitlake.daily.application.ai.DailyAiProviderMetadata
import com.fitlake.daily.application.ai.DailyMessageInterpretation
import com.fitlake.daily.application.ai.DailyMessageInterpretationOutcome
import com.fitlake.daily.application.ai.InterpretDailyMessageRequest
import com.fitlake.daily.application.ai.InterpretedDailyMessage
import java.util.concurrent.CopyOnWriteArrayList

sealed interface DailyAiScript {
	data class Interpret(val interpretation: DailyMessageInterpretation, val retryCount: Int = 0) : DailyAiScript
	data object Unresolved : DailyAiScript
	data object NoRelevantData : DailyAiScript
	data class Fail(val exception: RuntimeException) : DailyAiScript
}

data class ObservedDailyAiRequest(val request: InterpretDailyMessageRequest)

class ScriptedDailyAiInterpreter(
	override val metadata: DailyAiProviderMetadata = DailyAiProviderMetadata(
		provider = "test-provider",
		model = "test-model",
		promptVersion = "test-prompt-v3",
	),
) : CaptureInterpreterPort {
	private val observedRequests = CopyOnWriteArrayList<ObservedDailyAiRequest>()

	@Volatile
	private var nextScript: DailyAiScript = DailyAiScript.NoRelevantData

	val requests: List<ObservedDailyAiRequest>
		get() = observedRequests.toList()

	val callCount: Int
		get() = observedRequests.size

	fun script(script: DailyAiScript) {
		nextScript = script
	}

	fun reset(script: DailyAiScript = DailyAiScript.NoRelevantData) {
		observedRequests.clear()
		nextScript = script
	}

	override fun interpret(request: InterpretDailyMessageRequest): InterpretedDailyMessage {
		observedRequests += ObservedDailyAiRequest(request)
		return when (val selected = nextScript) {
			is DailyAiScript.Interpret -> InterpretedDailyMessage(
				selected.interpretation.withExactTestFragments(request.text),
				selected.retryCount,
			)
			DailyAiScript.Unresolved -> InterpretedDailyMessage(
				DailyMessageInterpretation(DailyMessageInterpretationOutcome.UNRESOLVED),
			)
			DailyAiScript.NoRelevantData -> InterpretedDailyMessage(
				DailyMessageInterpretation(DailyMessageInterpretationOutcome.NO_RELEVANT_DATA),
			)
			is DailyAiScript.Fail -> throw selected.exception
		}
	}

	private fun DailyMessageInterpretation.withExactTestFragments(rawText: String): DailyMessageInterpretation = copy(
		meals = meals.map { meal ->
			meal.copy(
				items = meal.items.map { food ->
					food.copy(originalFragment = food.originalFragment.takeIf(rawText::contains) ?: rawText)
				},
			)
		},
		fields = fields.map { field ->
			field.copy(originalFragment = field.originalFragment.takeIf(rawText::contains) ?: rawText)
		},
	)
}
