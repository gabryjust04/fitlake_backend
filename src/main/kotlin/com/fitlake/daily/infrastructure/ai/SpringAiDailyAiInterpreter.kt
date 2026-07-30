package com.fitlake.daily.infrastructure.ai

import com.fitlake.daily.application.ai.AiCaptureProposal
import com.fitlake.daily.application.ai.AiClarificationProposal
import com.fitlake.daily.application.ai.AiNoOpProposal
import com.fitlake.daily.application.ai.DailyAiException
import com.fitlake.daily.application.ai.DailyAiInterpreter
import com.fitlake.daily.application.ai.DailyAiInvalidOutputException
import com.fitlake.daily.application.ai.DailyAiProviderMetadata
import com.fitlake.daily.application.ai.DailyAiProviderUnavailableException
import com.fitlake.daily.application.ai.DailyAiRequestContext
import com.fitlake.daily.application.ai.DailyAiResult
import com.fitlake.daily.application.ai.DailyAiTerminalService
import com.fitlake.daily.application.ai.DailyAiTimeoutException
import org.springframework.ai.chat.messages.SystemMessage
import org.springframework.ai.chat.messages.UserMessage
import org.springframework.ai.chat.model.ChatModel
import org.springframework.ai.chat.model.ChatResponse
import org.springframework.ai.chat.model.ToolContext
import org.springframework.ai.chat.prompt.Prompt
import org.springframework.ai.model.tool.ToolCallingManager
import org.springframework.ai.openai.OpenAiChatOptions
import org.springframework.ai.tool.ToolCallback
import org.springframework.ai.tool.function.FunctionToolCallback
import org.springframework.ai.tool.metadata.ToolMetadata
import org.springframework.ai.util.JsonHelper
import org.slf4j.LoggerFactory
import java.net.SocketTimeoutException
import java.net.http.HttpTimeoutException
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.function.BiFunction

internal const val DAILY_AI_PROMPT_VERSION = "daily-capture-v1"
internal const val DAILY_AI_PROMPT_RESOURCE = "classpath:prompts/daily-capture-v1.txt"
internal const val DEFAULT_DAILY_AI_MAX_OUTPUT_TOKENS = 4096

internal class SpringAiDailyAiInterpreter(
	private val chatModel: ChatModel,
	private val toolCallingManager: ToolCallingManager,
	private val terminalService: DailyAiTerminalService,
	private val systemPrompt: String,
	override val metadata: DailyAiProviderMetadata,
	private val maxOutputTokens: Int = DEFAULT_DAILY_AI_MAX_OUTPUT_TOKENS,
) : DailyAiInterpreter {
	private val jsonHelper = JsonHelper()

	init {
		require(maxOutputTokens > 0) { "Daily AI max output tokens must be greater than zero" }
	}

	override fun interpret(context: DailyAiRequestContext, text: String): DailyAiResult {
		if (text.isBlank()) {
			throw DailyAiInvalidOutputException()
		}

		val terminalInvocation = TerminalInvocation(context, terminalService)
		val callbacks = terminalInvocation.toolCallbacks()
		val prompt = Prompt(
			listOf(SystemMessage(systemPrompt), UserMessage(userEnvelope(context, text))),
			OpenAiChatOptions.builder()
				.toolCallbacks(callbacks)
				.toolChoice("required")
				.parallelToolCalls(false)
				.temperature(0.0)
				.maxTokens(maxOutputTokens)
				.build(),
		)

		val response = callModel(prompt)
		validateSingleTerminalToolCall(response)

		try {
			val executionResult = toolCallingManager.executeToolCalls(prompt, response)
			terminalInvocation.failure()?.let { throw it }
			terminalInvocation.result()?.let { return it }
			if (!executionResult.returnDirect()) {
				throw DailyAiInvalidOutputException()
			}
			throw DailyAiInvalidOutputException()
		} catch (exception: RuntimeException) {
			terminalInvocation.failure()?.let { throw it }
			terminalInvocation.result()?.let { return it }
			if (exception is DailyAiException) {
				throw exception
			}
			throw DailyAiInvalidOutputException(exception)
		}
	}

	private fun userEnvelope(context: DailyAiRequestContext, text: String): String = jsonHelper.toJson(
		linkedMapOf(
			"targetDate" to context.date.toString(),
			"timezone" to context.timezone.id,
			"text" to text,
		),
	)

	private fun callModel(prompt: Prompt): ChatResponse = try {
		chatModel.call(prompt)
	} catch (exception: DailyAiException) {
		throw exception
	} catch (exception: RuntimeException) {
		if (exception.hasTimeoutCause()) {
			logger.warn(
				"Daily AI provider call timed out: provider={}, model={}, cause={}",
				metadata.provider,
				metadata.model,
				exception.diagnosticChain(),
			)
			throw DailyAiTimeoutException(exception)
		}
		logger.error(
			"Daily AI provider call failed: provider={}, model={}, cause={}",
			metadata.provider,
			metadata.model,
			exception.diagnosticChain(),
		)
		throw DailyAiProviderUnavailableException(exception)
	}

	private fun validateSingleTerminalToolCall(response: ChatResponse) {
		if (response.results.size != 1) {
			throw DailyAiInvalidOutputException()
		}
		val output = response.results.single().output
		val toolCalls = output.toolCalls
		if (
			toolCalls.size != 1 ||
			toolCalls.single().name() !in TERMINAL_TOOL_NAMES ||
			!output.text.isNullOrBlank()
		) {
			throw DailyAiInvalidOutputException()
		}
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

	private fun Throwable.diagnosticChain(): String {
		val visited = mutableSetOf<Throwable>()
		val parts = mutableListOf<String>()
		var current: Throwable? = this
		while (current != null && visited.add(current) && parts.size < MAX_DIAGNOSTIC_CAUSES) {
			val message = current.message
				?.redactSecrets()
				?.take(MAX_DIAGNOSTIC_MESSAGE_LENGTH)
				?.takeIf(String::isNotBlank)
			parts += if (message == null) {
				current.javaClass.simpleName
			} else {
				"${current.javaClass.simpleName}: $message"
			}
			current = current.cause
		}
		return parts.joinToString(" <- ")
	}

	private fun String.redactSecrets(): String = this
		.replace(BEARER_TOKEN_PATTERN, "\$1<redacted>")
		.replace(OPENAI_KEY_PATTERN, "<redacted-api-key>")

	private class TerminalInvocation(
		private val context: DailyAiRequestContext,
		private val terminalService: DailyAiTerminalService,
	) {
		private val claimed = AtomicBoolean(false)
		private val result = AtomicReference<DailyAiResult?>()
		private val failure = AtomicReference<RuntimeException?>()

		fun toolCallbacks(): List<ToolCallback> = listOf(
			FunctionToolCallback.builder<AiCaptureProposal, TerminalToolAcknowledgement>(
				CREATE_CAPTURE_TOOL,
				BiFunction { proposal: AiCaptureProposal, _: ToolContext ->
					execute("CAPTURE_CREATED") { terminalService.createCapture(context, proposal) }
				},
			)
				.description(
					"Create one pending Daily capture from sufficiently clear food, daily fields, mixed data, or a note.",
				)
				.inputType(AiCaptureProposal::class.java)
				.toolMetadata(DIRECT_RETURN_METADATA)
				.build(),
			FunctionToolCallback.builder<AiClarificationProposal, TerminalToolAcknowledgement>(
				ASK_CLARIFICATION_TOOL,
				BiFunction { proposal: AiClarificationProposal, _: ToolContext ->
					execute("CLARIFICATION_REQUIRED") { terminalService.askClarification(context, proposal) }
				},
			)
				.description(
					"Ask one concise clarification only when a potentially useful Daily entry lacks necessary information.",
				)
				.inputType(AiClarificationProposal::class.java)
				.toolMetadata(DIRECT_RETURN_METADATA)
				.build(),
			FunctionToolCallback.builder<AiNoOpProposal, TerminalToolAcknowledgement>(
				NO_OP_TOOL,
				BiFunction { proposal: AiNoOpProposal, _: ToolContext ->
					execute("NO_OP") { terminalService.noOp(context, proposal) }
				},
			)
				.description("Return no-op when the message contains no usable Daily record.")
				.inputType(AiNoOpProposal::class.java)
				.toolMetadata(DIRECT_RETURN_METADATA)
				.build(),
		)

		fun result(): DailyAiResult? = result.get()

		fun failure(): RuntimeException? = failure.get()

		private fun execute(
			outcome: String,
			operation: () -> DailyAiResult,
		): TerminalToolAcknowledgement {
			if (!claimed.compareAndSet(false, true)) {
				throw DailyAiInvalidOutputException()
			}
			return try {
				operation().also(result::set)
				TerminalToolAcknowledgement(outcome)
			} catch (exception: RuntimeException) {
				failure.compareAndSet(null, exception)
				throw exception
			}
		}
	}

	private data class TerminalToolAcknowledgement(val outcome: String)

	private companion object {
		const val CREATE_CAPTURE_TOOL = "createCapture"
		const val ASK_CLARIFICATION_TOOL = "askClarification"
		const val NO_OP_TOOL = "noOp"

		val TERMINAL_TOOL_NAMES = setOf(CREATE_CAPTURE_TOOL, ASK_CLARIFICATION_TOOL, NO_OP_TOOL)
		val DIRECT_RETURN_METADATA: ToolMetadata = ToolMetadata.builder().returnDirect(true).build()
		const val MAX_DIAGNOSTIC_CAUSES = 8
		const val MAX_DIAGNOSTIC_MESSAGE_LENGTH = 2_000
		val BEARER_TOKEN_PATTERN = Regex("(?i)(Bearer\\s+)[^\\s,;]+")
		val OPENAI_KEY_PATTERN = Regex("(?i)\\bsk-[a-z0-9_-]{12,}\\b")
		val logger = LoggerFactory.getLogger(SpringAiDailyAiInterpreter::class.java)
	}
}
