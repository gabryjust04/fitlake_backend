package com.fitlake.daily.infrastructure.ai

import ch.qos.logback.classic.Level
import com.openai.core.http.Headers
import com.openai.errors.UnexpectedStatusCodeException
import com.fitlake.daily.application.ai.DailyAiInvalidOutputException
import com.fitlake.daily.application.ai.DailyAiProviderAuthenticationException
import com.fitlake.daily.application.ai.DailyAiProviderMetadata
import com.fitlake.daily.application.ai.DailyAiProviderQuotaException
import com.fitlake.daily.application.ai.DailyAiProviderUnavailableException
import com.fitlake.daily.application.ai.DailyAiRateLimitException
import com.fitlake.daily.application.ai.DailyAiTimeoutException
import com.fitlake.daily.application.ai.DailyMessageInterpretationOutcome
import com.fitlake.daily.application.ai.InterpretDailyMessageRequest
import com.fitlake.daily.application.ai.InterpretedDailyMessage
import com.fitlake.support.LogEventCapture
import com.fitlake.support.renderedLogContent
import com.fitlake.support.structuredFields
import org.junit.jupiter.api.Test
import org.springframework.ai.chat.messages.AssistantMessage
import org.springframework.ai.chat.metadata.ChatResponseMetadata
import org.springframework.ai.chat.metadata.DefaultUsage
import org.springframework.ai.chat.model.ChatModel
import org.springframework.ai.chat.model.ChatResponse
import org.springframework.ai.chat.model.Generation
import org.springframework.ai.chat.prompt.Prompt
import org.springframework.ai.openai.OpenAiChatModel
import org.springframework.ai.openai.OpenAiChatOptions
import org.springframework.ai.util.JsonHelper
import org.springframework.core.io.DefaultResourceLoader
import java.net.SocketTimeoutException
import java.nio.charset.StandardCharsets
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class SpringAiDailyAiInterpreterTest {
	private val request = InterpretDailyMessageRequest(
		targetDate = LocalDate.parse("2026-07-30"),
		timezone = ZoneId.of("Europe/Rome"),
		text = "A colazione ho mangiato 40 g avena",
	)

	@Test
	fun `structured JSON is parsed without tools and reports provider usage`() {
		lateinit var observedPrompt: Prompt
		val chatModel = ScriptedChatModel { prompt ->
			observedPrompt = prompt
			textResponse(VALID_COMPLETE_OUTPUT, promptTokens = 17, completionTokens = 11)
		}

		val result = interpreter(chatModel).interpret(request)

		assertEquals(DailyMessageInterpretationOutcome.COMPLETE, result.interpretation.outcome)
		assertEquals("40 g avena", result.interpretation.meals.single().items.single().originalFragment)
		assertEquals(0, result.retryCount)
		assertEquals(17L, result.inputTokens)
		assertEquals(11L, result.outputTokens)
		assertEquals(1, chatModel.callCount)

		val envelope = JsonHelper().fromJsonToMap(requireNotNull(observedPrompt.userMessage.text))
		assertEquals("2026-07-30", envelope["targetDate"])
		assertEquals("Europe/Rome", envelope["timezone"])
		assertEquals(request.text, envelope["text"])
		assertEquals(setOf("targetDate", "timezone", "text"), envelope.keys)

		val options = observedPrompt.options as OpenAiChatOptions
		assertTrue(options.toolCallbacks.isNullOrEmpty())
		assertNull(options.toolChoice)
		assertEquals(4096, options.maxTokens)
		val responseFormat = requireNotNull(options.responseFormat)
		assertEquals(OpenAiChatModel.ResponseFormat.Type.JSON_SCHEMA, responseFormat.type)
		val schema = requireNotNull(responseFormat.jsonSchema)
		assertContains(schema, "originalFragment")
		assertContains(schema, "estimatedQuantity")
		assertContains(schema, "nutritionEstimate")
		FORBIDDEN_MODEL_CONTROLLED_FIELDS.forEach { forbidden ->
			assertFalse(schema.contains("\"$forbidden\"", ignoreCase = true), "$forbidden leaked into the schema")
		}
	}

	@Test
	fun `invalid exact fragment is corrected once and usage is accumulated`() {
		val prompts = mutableListOf<Prompt>()
		val chatModel = QueuedChatModel(
			listOf(
				textResponse(INVALID_FRAGMENT_OUTPUT, promptTokens = 10, completionTokens = 4),
				textResponse(VALID_COMPLETE_OUTPUT, promptTokens = 12, completionTokens = 6),
			),
			prompts,
		)

		lateinit var result: InterpretedDailyMessage
		val logEvents = LogEventCapture(SpringAiDailyAiInterpreter::class.java, Level.DEBUG).use { logs ->
			result = interpreter(chatModel, maxCorrectionRetries = 1).interpret(request)
			logs.events
		}

		assertEquals(1, result.retryCount)
		assertEquals(22L, result.inputTokens)
		assertEquals(10L, result.outputTokens)
		assertEquals(2, chatModel.callCount)
		assertFalse(requireNotNull(prompts.first().systemMessage.text).contains("previous response failed", ignoreCase = true))
		assertTrue(requireNotNull(prompts.last().systemMessage.text).contains("previous response failed", ignoreCase = true))
		assertFalse(requireNotNull(prompts.last().systemMessage.text).contains("41 g avena"))

		val retryFields = logEvents
			.single { it.structuredFields()["event"] == "daily_ai_schema_retry" }
			.structuredFields()
		assertEquals("retry", retryFields["outcome"])
		assertEquals("AI_OUTPUT_SCHEMA_INVALID", retryFields["errorCode"])
		assertEquals(METADATA.provider, retryFields["provider"])
		assertEquals("test-model", retryFields["model"])
		assertEquals(DAILY_AI_PROMPT_VERSION, retryFields["promptVersion"])
		assertEquals(1, retryFields["attempt"])
		assertTrue((retryFields["reasonType"] as? String).orEmpty().isNotBlank())
		assertFalse(retryFields.containsKey("rawResponse"))
		val renderedLogs = logEvents.renderedLogContent()
		assertFalse(renderedLogs.contains(request.text))
		assertFalse(renderedLogs.contains("41 g avena"))
		assertFalse(renderedLogs.contains("nutritionEstimate"))
	}

	@Test
	fun `prompt enforced schema works when the OpenRouter model does not support native response format`() {
		val prompts = mutableListOf<Prompt>()
		val chatModel = QueuedChatModel(listOf(textResponse(VALID_COMPLETE_OUTPUT)), prompts)

		interpreter(chatModel, nativeStructuredOutputEnabled = false).interpret(request)

		val prompt = prompts.single()
		val options = prompt.options as OpenAiChatOptions
		assertNull(options.responseFormat)
		assertContains(requireNotNull(prompt.systemMessage.text), "originalFragment")
		assertTrue(options.toolCallbacks.isNullOrEmpty())
	}

	@Test
	fun `bounded correction retry ends in invalid output without accepting paraphrased fragments`() {
		val chatModel = QueuedChatModel(
			listOf(textResponse(INVALID_FRAGMENT_OUTPUT), textResponse(INVALID_FRAGMENT_OUTPUT)),
		)

		assertFailsWith<DailyAiInvalidOutputException> {
			interpreter(chatModel, maxCorrectionRetries = 1).interpret(request)
		}

		assertEquals(2, chatModel.callCount)
	}

	@Test
	fun `missing provider usage remains unknown instead of becoming zero`() {
		val result = interpreter(ScriptedChatModel { textResponse(VALID_COMPLETE_OUTPUT) }).interpret(request)

		assertNull(result.inputTokens)
		assertNull(result.outputTokens)
	}

	@Test
	fun `outcome invariants are validated before returning the interpretation`() {
		val invalidNoRelevant = VALID_COMPLETE_OUTPUT.replace("\"COMPLETE\"", "\"NO_RELEVANT_DATA\"")
		val chatModel = ScriptedChatModel { textResponse(invalidNoRelevant) }

		assertFailsWith<DailyAiInvalidOutputException> {
			interpreter(chatModel, maxCorrectionRetries = 0).interpret(request)
		}
	}

	@Test
	fun `unknown model controlled fields are rejected instead of silently ignored`() {
		val outputWithBackendId = VALID_COMPLETE_OUTPUT.replace(
			"\"outcome\": \"COMPLETE\"",
			"\"captureId\": \"model-owned-id\", \"outcome\": \"COMPLETE\"",
		)
		val chatModel = ScriptedChatModel { textResponse(outputWithBackendId) }

		assertFailsWith<DailyAiInvalidOutputException> {
			interpreter(chatModel, maxCorrectionRetries = 0).interpret(request)
		}

		assertEquals(1, chatModel.callCount)
	}

	@Test
	fun `estimated quantity must be directly scalable from its nutrition basis`() {
		val incompatibleEstimate = VALID_COMPLETE_OUTPUT.replace(
			"\"estimatedQuantity\": {\"amount\": 40, \"unit\": \"g\"}",
			"\"estimatedQuantity\": {\"amount\": 1, \"unit\": \"unit\"}",
		)
		val chatModel = ScriptedChatModel { textResponse(incompatibleEstimate) }

		assertFailsWith<DailyAiInvalidOutputException> {
			interpreter(chatModel, maxCorrectionRetries = 0).interpret(request)
		}

		assertEquals(1, chatModel.callCount)
	}

	@Test
	fun `tool calls are rejected and never registered in provider options`() {
		val prompts = mutableListOf<Prompt>()
		val toolResponse = ChatResponse(
			listOf(
				Generation(
					AssistantMessage.builder()
						.content("")
						.toolCalls(
							listOf(AssistantMessage.ToolCall("call-1", "function", "createCapture", "{}")),
						)
						.build(),
				),
			),
		)
		val chatModel = QueuedChatModel(listOf(toolResponse, toolResponse), prompts)

		assertFailsWith<DailyAiInvalidOutputException> {
			interpreter(chatModel, maxCorrectionRetries = 1).interpret(request)
		}

		assertEquals(2, chatModel.callCount)
		prompts.forEach { prompt ->
			val options = prompt.options as OpenAiChatOptions
			assertTrue(options.toolCallbacks.isNullOrEmpty())
			assertNull(options.toolChoice)
		}
	}

	@Test
	fun `timeout is mapped without retrying structured output`() {
		val providerFailure = IllegalStateException(SocketTimeoutException("timed out with secret text"))
		val chatModel = ScriptedChatModel { throw providerFailure }

		val exception = assertFailsWith<DailyAiTimeoutException> {
			interpreter(chatModel).interpret(request)
		}

		assertSame(providerFailure, exception.cause)
		assertEquals(1, chatModel.callCount)
	}

	@Test
	fun `provider failure is translated without duplicate adapter logging`() {
		val providerFailure = IllegalStateException(
			"HTTP 400 Authorization: Bearer exposed-token; key=provider-secret-token; ${request.text}",
		)
		val chatModel = ScriptedChatModel { throw providerFailure }

		lateinit var exception: DailyAiProviderUnavailableException
		val logEvents = LogEventCapture(SpringAiDailyAiInterpreter::class.java, Level.DEBUG).use { logs ->
			exception = assertFailsWith<DailyAiProviderUnavailableException> {
				interpreter(chatModel).interpret(request)
			}
			logs.events
		}

		assertSame(providerFailure, exception.cause)
		assertTrue(logEvents.isEmpty(), "The application boundary owns the single provider failure event")
	}

	@Test
	fun `provider HTTP statuses are normalized without parsing raw response messages`() {
		val expectedTypes = mapOf(
			401 to DailyAiProviderAuthenticationException::class,
			402 to DailyAiProviderQuotaException::class,
			408 to DailyAiTimeoutException::class,
			429 to DailyAiRateLimitException::class,
			503 to DailyAiProviderUnavailableException::class,
		)

		expectedTypes.forEach { (status, expectedType) ->
			val sdkFailure = UnexpectedStatusCodeException.builder()
				.statusCode(status)
				.headers(Headers.builder().build())
				.build()
			val wrappedFailure = IllegalStateException("private provider response body", sdkFailure)
			val thrown = assertFails {
				interpreter(ScriptedChatModel { throw wrappedFailure }).interpret(request)
			}

			assertEquals(expectedType, thrown::class, "Unexpected mapping for HTTP $status")
			assertSame(wrappedFailure, thrown.cause)
		}
	}

	@Test
	fun `v3 prompt defines pure structured outcomes quantities nutrition and exact fragments`() {
		assertEquals("daily-capture-v3", DAILY_AI_PROMPT_VERSION)
		assertEquals("classpath:prompts/daily-capture-v3.txt", DAILY_AI_PROMPT_RESOURCE)
		val prompt = DefaultResourceLoader()
			.getResource(DAILY_AI_PROMPT_RESOURCE)
			.getContentAsString(StandardCharsets.UTF_8)

		assertContains(prompt, "Never call tools")
		assertContains(prompt, "COMPLETE")
		assertContains(prompt, "PARTIAL")
		assertContains(prompt, "UNRESOLVED")
		assertContains(prompt, "NO_RELEVANT_DATA")
		assertContains(prompt, "originalFragment")
		assertContains(prompt, "statedQuantity")
		assertContains(prompt, "estimatedQuantity")
		assertContains(prompt, "nutritionEstimate.basis")
		assertContains(prompt, "backend performs final scaling")
		assertFalse(prompt.contains("askClarification"))
		assertFalse(prompt.contains("createCapture"))
	}

	private fun interpreter(
		chatModel: ChatModel,
		maxCorrectionRetries: Int = DEFAULT_DAILY_AI_MAX_CORRECTION_RETRIES,
		nativeStructuredOutputEnabled: Boolean = true,
	): SpringAiDailyAiInterpreter = SpringAiDailyAiInterpreter(
		chatModel = chatModel,
		systemPrompt = "Return a pure structured Daily interpretation.",
		metadata = METADATA,
		maxCorrectionRetries = maxCorrectionRetries,
		nativeStructuredOutputEnabled = nativeStructuredOutputEnabled,
	)

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

	private class QueuedChatModel(
		responses: List<ChatResponse>,
		private val observedPrompts: MutableList<Prompt> = mutableListOf(),
	) : ChatModel {
		private val responses = ArrayDeque(responses)
		var callCount: Int = 0
			private set

		override fun call(prompt: Prompt): ChatResponse {
			callCount += 1
			observedPrompts += prompt
			return responses.removeFirst()
		}
	}

	private companion object {
		val METADATA = DailyAiProviderMetadata(
			provider = "test-provider",
			model = "test-model",
			promptVersion = DAILY_AI_PROMPT_VERSION,
		)
		val VALID_COMPLETE_OUTPUT = """
			{
			  "outcome": "COMPLETE",
			  "meals": [
			    {
			      "mealName": "colazione",
			      "items": [
			        {
			          "originalFragment": "40 g avena",
			          "searchText": "avena",
			          "statedQuantity": {"amount": 40, "unit": "g"},
			          "estimatedQuantity": {"amount": 40, "unit": "g"},
			          "nutritionEstimate": {
			            "basis": {"amount": 100, "unit": "g"},
			            "caloriesKcal": 389,
			            "proteinGrams": 16.9,
			            "carbohydratesGrams": 66.3,
			            "fatGrams": 6.9
			          },
			          "assumptions": ["dry rolled oats"]
			        }
			      ]
			    }
			  ],
			  "fields": [],
			  "unresolvedFragments": [],
			  "confidence": 0.95
			}
		""".trimIndent()
		val INVALID_FRAGMENT_OUTPUT = VALID_COMPLETE_OUTPUT.replace("40 g avena", "41 g avena")
		val FORBIDDEN_MODEL_CONTROLLED_FIELDS = setOf(
			"userId",
			"firebaseUid",
			"date",
			"dayId",
			"captureId",
			"entryId",
			"itemId",
			"sourceEventId",
			"status",
			"createdAt",
			"acceptedAt",
			"rejectedAt",
		)

		fun textResponse(
			text: String,
			promptTokens: Int? = null,
			completionTokens: Int? = null,
		): ChatResponse {
			val metadata = ChatResponseMetadata.builder()
				.apply {
					if (promptTokens != null || completionTokens != null) {
						usage(DefaultUsage(promptTokens ?: 0, completionTokens ?: 0))
					}
				}
				.build()
			return ChatResponse(listOf(Generation(AssistantMessage(text))), metadata)
		}
	}
}
