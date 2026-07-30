package com.fitlake.daily.infrastructure.ai

import com.fitlake.daily.application.ai.AiCaptureProposal
import com.fitlake.daily.application.ai.DailyAiInvalidOutputException
import com.fitlake.daily.application.ai.DailyAiPersistenceException
import com.fitlake.daily.application.ai.DailyAiProviderMetadata
import com.fitlake.daily.application.ai.DailyAiProviderUnavailableException
import com.fitlake.daily.application.ai.DailyAiRequestContext
import com.fitlake.daily.application.ai.DailyAiResult
import com.fitlake.daily.application.ai.DailyAiTerminalService
import com.fitlake.daily.application.ai.DailyAiTimeoutException
import com.fitlake.daily.application.ai.toAiCaptureResult
import com.fitlake.daily.domain.capture.DailyCapture
import com.fitlake.daily.domain.capture.DailyCapturePayload
import com.fitlake.daily.domain.capture.DailyCaptureType
import com.fitlake.daily.domain.common.DailyDayId
import com.fitlake.daily.domain.inbox.DailyInboxEventId
import com.fitlake.user.domain.UserId
import org.junit.jupiter.api.Test
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.doThrow
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.ai.chat.messages.AssistantMessage
import org.springframework.ai.chat.model.ChatModel
import org.springframework.ai.chat.model.ChatResponse
import org.springframework.ai.chat.model.Generation
import org.springframework.ai.chat.model.ToolContext
import org.springframework.ai.chat.prompt.Prompt
import org.springframework.ai.model.tool.ToolCallingChatOptions
import org.springframework.ai.model.tool.ToolCallingManager
import org.springframework.ai.model.tool.ToolExecutionResult
import org.springframework.ai.openai.OpenAiChatOptions
import org.springframework.ai.tool.definition.ToolDefinition
import org.springframework.ai.util.JsonHelper
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import java.math.BigDecimal
import java.net.SocketTimeoutException
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

@ExtendWith(OutputCaptureExtension::class)
class SpringAiDailyAiInterpreterTest {
	private val terminalService = mock(DailyAiTerminalService::class.java)
	private val context = DailyAiRequestContext(
		inboxEventId = DailyInboxEventId(UUID.fromString("10000000-0000-0000-0000-000000000001")),
		userId = UserId(UUID.fromString("20000000-0000-0000-0000-000000000002")),
		date = LocalDate.parse("2026-07-30"),
		timezone = ZoneId.of("Europe/Rome"),
		replacesCaptureId = null,
		metadata = METADATA,
		startedAt = Instant.parse("2026-07-30T08:00:00Z"),
		processingAttemptId = UUID.fromString("40000000-0000-0000-0000-000000000004"),
	)

	@Test
	fun `one valid createCapture tool is executed and returns its terminal result`() {
		val expected = captureCreatedResult()
		val expectedProposal = AiCaptureProposal(
			type = "NOTE",
			note = "Oggi mi sento bene",
			confidence = BigDecimal("0.95"),
		)
		doReturn(expected).`when`(terminalService).createCapture(context, expectedProposal)
		val chatModel = ScriptedChatModel {
			toolResponse(toolCall("createCapture", VALID_CREATE_CAPTURE_ARGUMENTS))
		}
		val interpreter = interpreter(chatModel)

		val actual = interpreter.interpret(context, "Oggi mi sento bene")

		assertSame(expected, actual)
		verify(terminalService).createCapture(context, expectedProposal)
		assertEquals(1, chatModel.callCount)
	}

	@Test
	fun `terminal result survives a framework failure raised after the callback`() {
		val expected = captureCreatedResult()
		val expectedProposal = createCaptureProposal()
		doReturn(expected).`when`(terminalService).createCapture(context, expectedProposal)
		val response = toolResponse(toolCall("createCapture", VALID_CREATE_CAPTURE_ARGUMENTS))
		val toolCallingManager = ScriptedToolCallingManager { prompt, chatResponse ->
			executeTerminalCallback(prompt, chatResponse)
			throw IllegalStateException("post-callback conversion failed")
		}
		val interpreter = interpreter(ScriptedChatModel { response }, toolCallingManager)

		val actual = interpreter.interpret(context, "Oggi mi sento bene")

		assertSame(expected, actual)
		verify(terminalService).createCapture(context, expectedProposal)
	}

	@Test
	fun `terminal result wins over an inconsistent non-direct execution result`() {
		val expected = captureCreatedResult()
		val expectedProposal = createCaptureProposal()
		doReturn(expected).`when`(terminalService).createCapture(context, expectedProposal)
		val response = toolResponse(toolCall("createCapture", VALID_CREATE_CAPTURE_ARGUMENTS))
		val toolCallingManager = ScriptedToolCallingManager { prompt, chatResponse ->
			executeTerminalCallback(prompt, chatResponse)
			ToolExecutionResult.builder()
				.conversationHistory(prompt.instructions)
				.returnDirect(false)
				.build()
		}
		val interpreter = interpreter(ScriptedChatModel { response }, toolCallingManager)

		val actual = interpreter.interpret(context, "Oggi mi sento bene")

		assertSame(expected, actual)
		verify(terminalService).createCapture(context, expectedProposal)
	}

	@Test
	fun `terminal failure is not masked by a later framework failure`() {
		val terminalFailure = DailyAiPersistenceException()
		val expectedProposal = createCaptureProposal()
		doThrow(terminalFailure).`when`(terminalService).createCapture(context, expectedProposal)
		val response = toolResponse(toolCall("createCapture", VALID_CREATE_CAPTURE_ARGUMENTS))
		val toolCallingManager = ScriptedToolCallingManager { prompt, chatResponse ->
			try {
				executeTerminalCallback(prompt, chatResponse)
			} catch (_: RuntimeException) {
				// Simulate framework processing that catches the callback failure before failing itself.
			}
			throw IllegalStateException("framework failure")
		}
		val interpreter = interpreter(ScriptedChatModel { response }, toolCallingManager)

		val actual = assertFailsWith<DailyAiPersistenceException> {
			interpreter.interpret(context, "Oggi mi sento bene")
		}

		assertSame(terminalFailure, actual)
	}

	@Test
	fun `zero tool calls are rejected without terminal side effects`() {
		val interpreter = interpreter(ScriptedChatModel { textResponse("") })

		assertFailsWith<DailyAiInvalidOutputException> {
			interpreter.interpret(context, "testo")
		}

		verifyNoInteractions(terminalService)
	}

	@Test
	fun `multiple tool calls are rejected before any terminal side effect`() {
		val interpreter = interpreter(
			ScriptedChatModel {
				toolResponse(
					toolCall("createCapture", VALID_CREATE_CAPTURE_ARGUMENTS, "call-create"),
					toolCall("noOp", """{"reason":"Nessun dato"}""", "call-noop"),
				)
			},
		)

		assertFailsWith<DailyAiInvalidOutputException> {
			interpreter.interpret(context, "testo")
		}

		verifyNoInteractions(terminalService)
	}

	@Test
	fun `multiple generations are rejected before any terminal side effect`() {
		val interpreter = interpreter(
			ScriptedChatModel {
				ChatResponse(
					listOf(
						Generation(toolMessage(toolCall("createCapture", VALID_CREATE_CAPTURE_ARGUMENTS))),
						Generation(AssistantMessage("")),
					),
				)
			},
		)

		assertFailsWith<DailyAiInvalidOutputException> {
			interpreter.interpret(context, "testo")
		}

		verifyNoInteractions(terminalService)
	}

	@Test
	fun `free text alongside one terminal tool is rejected before any terminal side effect`() {
		val interpreter = interpreter(
			ScriptedChatModel {
				ChatResponse(
					listOf(
						Generation(
							toolMessage(
								toolCall("createCapture", VALID_CREATE_CAPTURE_ARGUMENTS),
								text = "Ho preparato la proposta.",
							),
						),
					),
				)
			},
		)

		assertFailsWith<DailyAiInvalidOutputException> {
			interpreter.interpret(context, "testo")
		}

		verifyNoInteractions(terminalService)
	}

	@Test
	fun `unknown tool is rejected before any terminal side effect`() {
		val interpreter = interpreter(
			ScriptedChatModel { toolResponse(toolCall("deleteEverything", "{}")) },
		)

		assertFailsWith<DailyAiInvalidOutputException> {
			interpreter.interpret(context, "testo")
		}

		verifyNoInteractions(terminalService)
	}

	@Test
	fun `invalid tool JSON is rejected without terminal side effects`() {
		val interpreter = interpreter(
			ScriptedChatModel { toolResponse(toolCall("createCapture", "not-json")) },
		)

		assertFailsWith<DailyAiInvalidOutputException> {
			interpreter.interpret(context, "testo")
		}

		verifyNoInteractions(terminalService)
	}

	@Test
	fun `timeout cause is mapped without executing a tool`() {
		val interpreter = interpreter(
			ScriptedChatModel { throw IllegalStateException(SocketTimeoutException("timed out")) },
		)

		assertFailsWith<DailyAiTimeoutException> {
			interpreter.interpret(context, "testo")
		}

		verifyNoInteractions(terminalService)
	}

	@Test
	fun `provider failure is mapped and safely logged without executing a tool`(output: CapturedOutput) {
		val providerFailure = IllegalStateException(
			"HTTP 400 invalid tools; Authorization: Bearer exposed-token; key=sk-or-v1-secretsecretsecret",
		)
		val interpreter = interpreter(ScriptedChatModel { throw providerFailure })

		val exception = assertFailsWith<DailyAiProviderUnavailableException> {
			interpreter.interpret(context, "testo")
		}

		assertSame(providerFailure, exception.cause)
		assertTrue(output.all.contains("Daily AI provider call failed"))
		assertTrue(output.all.contains("HTTP 400 invalid tools"))
		assertFalse(output.all.contains("exposed-token"))
		assertFalse(output.all.contains("sk-or-v1-secretsecretsecret"))
		verifyNoInteractions(terminalService)
	}

	@Test
	fun `prompt exposes safe schemas and an exact backend context envelope`() {
		val originalText = "Ho dormito 7 ore e il testo contiene \"virgolette\""
		lateinit var observedPrompt: Prompt
		val chatModel = ScriptedChatModel { prompt ->
			observedPrompt = prompt
			textResponse("ignored")
		}
		val interpreter = interpreter(chatModel)

		assertFailsWith<DailyAiInvalidOutputException> {
			interpreter.interpret(context, originalText)
		}

		val envelope = JsonHelper().fromJsonToMap(requireNotNull(observedPrompt.userMessage.text))
		assertEquals("2026-07-30", envelope["targetDate"])
		assertEquals("Europe/Rome", envelope["timezone"])
		assertEquals(originalText, envelope["text"])
		assertEquals(setOf("targetDate", "timezone", "text"), envelope.keys)

		val options = observedPrompt.options as ToolCallingChatOptions
		val openAiOptions = observedPrompt.options as OpenAiChatOptions
		val toolCallbacks = requireNotNull(options.toolCallbacks)
		assertEquals(3, toolCallbacks.size)
		assertEquals(4096, openAiOptions.maxTokens)
		val schemas = toolCallbacks.joinToString("\n") { it.toolDefinition.inputSchema() }
		FORBIDDEN_MODEL_CONTROLLED_FIELDS.forEach { forbidden ->
			assertFalse(schemas.contains(forbidden, ignoreCase = true), "$forbidden leaked into a tool schema")
		}
		assertTrue(schemas.contains("foodName"))
		assertTrue(schemas.contains("sleepHours"))
	}

	private fun interpreter(
		chatModel: ChatModel,
		toolCallingManager: ToolCallingManager = ToolCallingManager.builder().build(),
	): SpringAiDailyAiInterpreter = SpringAiDailyAiInterpreter(
		chatModel = chatModel,
		toolCallingManager = toolCallingManager,
		terminalService = terminalService,
		systemPrompt = "Choose exactly one terminal Daily tool.",
		metadata = METADATA,
	)

	private fun captureCreatedResult(): DailyAiResult.CaptureCreated {
		val at = Instant.parse("2026-07-30T08:01:00Z")
		val capture = DailyCapture.openFromAi(
			userId = context.userId,
			dayId = DailyDayId(UUID.fromString("30000000-0000-0000-0000-000000000003")),
			sourceEventId = context.inboxEventId.value,
			payload = DailyCapturePayload(type = DailyCaptureType.NOTE, note = "Oggi mi sento bene"),
			confidence = BigDecimal("0.95"),
			at = at,
		)
		return DailyAiResult.CaptureCreated(context.date, capture.toAiCaptureResult(), null)
	}

	private fun createCaptureProposal() = AiCaptureProposal(
		type = "NOTE",
		note = "Oggi mi sento bene",
		confidence = BigDecimal("0.95"),
	)

	private fun executeTerminalCallback(prompt: Prompt, response: ChatResponse) {
		val toolCall = response.results.single().output.toolCalls.single()
		val callbacks = requireNotNull((prompt.options as ToolCallingChatOptions).toolCallbacks)
		val callback = callbacks.single { it.toolDefinition.name() == toolCall.name() }
		callback.call(toolCall.arguments(), ToolContext(emptyMap()))
	}

	private class ScriptedChatModel(
		private val response: (Prompt) -> ChatResponse,
	) : ChatModel {
		var callCount: Int = 0
			private set

		override fun call(prompt: Prompt): ChatResponse {
			callCount += 1
			return response(prompt)
		}
	}

	private class ScriptedToolCallingManager(
		private val execution: (Prompt, ChatResponse) -> ToolExecutionResult,
	) : ToolCallingManager {
		override fun resolveToolDefinitions(options: ToolCallingChatOptions): List<ToolDefinition> = emptyList()

		override fun executeToolCalls(prompt: Prompt, chatResponse: ChatResponse): ToolExecutionResult =
			execution(prompt, chatResponse)
	}

	private companion object {
		val METADATA = DailyAiProviderMetadata(
			provider = "test-provider",
			model = "test-model",
			promptVersion = DAILY_AI_PROMPT_VERSION,
		)
		val VALID_CREATE_CAPTURE_ARGUMENTS = """
			{
			  "type": "NOTE",
			  "meals": [],
			  "fields": {},
			  "note": "Oggi mi sento bene",
			  "confidence": 0.95
			}
		""".trimIndent()
		val FORBIDDEN_MODEL_CONTROLLED_FIELDS = setOf(
			"userId",
			"firebaseUid",
			"date",
			"dayId",
			"captureId",
			"mealId",
			"itemId",
			"sourceEventId",
			"status",
			"createdAt",
			"acceptedAt",
			"rejectedAt",
		)

		fun toolCall(
			name: String,
			arguments: String,
			id: String = "call-1",
		): AssistantMessage.ToolCall = AssistantMessage.ToolCall(id, "function", name, arguments)

		fun toolResponse(vararg toolCalls: AssistantMessage.ToolCall): ChatResponse = ChatResponse(
			listOf(Generation(toolMessage(*toolCalls))),
		)

		fun toolMessage(
			vararg toolCalls: AssistantMessage.ToolCall,
			text: String = "",
		): AssistantMessage = AssistantMessage.builder()
			.content(text)
			.toolCalls(toolCalls.toList())
			.build()

		fun textResponse(text: String): ChatResponse = ChatResponse(
			listOf(Generation(AssistantMessage(text))),
		)
	}
}
