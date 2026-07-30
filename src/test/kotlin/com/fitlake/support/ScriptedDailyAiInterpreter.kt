package com.fitlake.support

import com.fitlake.daily.application.ai.AiCaptureProposal
import com.fitlake.daily.application.ai.AiClarificationProposal
import com.fitlake.daily.application.ai.AiNoOpProposal
import com.fitlake.daily.application.ai.DailyAiInterpreter
import com.fitlake.daily.application.ai.DailyAiProviderMetadata
import com.fitlake.daily.application.ai.DailyAiRequestContext
import com.fitlake.daily.application.ai.DailyAiResult
import com.fitlake.daily.application.ai.DailyAiTerminalService
import java.util.concurrent.CopyOnWriteArrayList

sealed interface DailyAiScript {
	data class CreateCapture(val proposal: AiCaptureProposal) : DailyAiScript
	data class AskClarification(val question: String?) : DailyAiScript
	data class NoOp(val reason: String?) : DailyAiScript
	data class Fail(val exception: RuntimeException) : DailyAiScript
}

data class ObservedDailyAiRequest(
	val context: DailyAiRequestContext,
	val text: String,
)

class ScriptedDailyAiInterpreter(
	private val terminalService: DailyAiTerminalService,
	override val metadata: DailyAiProviderMetadata = DailyAiProviderMetadata(
		provider = "test-provider",
		model = "test-model",
		promptVersion = "test-prompt-v1",
	),
) : DailyAiInterpreter {
	private val observedRequests = CopyOnWriteArrayList<ObservedDailyAiRequest>()

	@Volatile
	private var nextScript: DailyAiScript = DailyAiScript.NoOp("No Daily data")

	val requests: List<ObservedDailyAiRequest>
		get() = observedRequests.toList()

	val callCount: Int
		get() = observedRequests.size

	fun script(script: DailyAiScript) {
		nextScript = script
	}

	fun reset(script: DailyAiScript = DailyAiScript.NoOp("No Daily data")) {
		observedRequests.clear()
		nextScript = script
	}

	override fun interpret(context: DailyAiRequestContext, text: String): DailyAiResult {
		observedRequests += ObservedDailyAiRequest(context, text)
		return when (val selected = nextScript) {
			is DailyAiScript.CreateCapture -> terminalService.createCapture(context, selected.proposal)
			is DailyAiScript.AskClarification -> terminalService.askClarification(
				context,
				AiClarificationProposal(selected.question),
			)
			is DailyAiScript.NoOp -> terminalService.noOp(context, AiNoOpProposal(selected.reason))
			is DailyAiScript.Fail -> throw selected.exception
		}
	}
}
