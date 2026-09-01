package com.fitlake.daily.infrastructure.ai

import com.fitlake.daily.application.ai.CaptureInterpreterPort
import com.fitlake.daily.application.ai.DailyAiConfigurationException
import com.fitlake.daily.application.ai.DailyAiInvalidOutputException
import com.fitlake.daily.application.ai.InterpretDailyMessageRequest
import com.fitlake.support.LogEventCapture
import com.fitlake.support.renderedLogContent
import com.fitlake.support.structuredFields
import org.junit.jupiter.api.Test
import org.springframework.ai.chat.messages.AssistantMessage
import org.springframework.ai.chat.model.ChatModel
import org.springframework.ai.chat.model.ChatResponse
import org.springframework.ai.chat.model.Generation
import org.springframework.ai.chat.prompt.ChatOptions
import org.springframework.ai.chat.prompt.Prompt
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.support.DefaultListableBeanFactory
import org.springframework.core.io.DefaultResourceLoader
import org.springframework.mock.env.MockEnvironment
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DailyAiSpringConfigurationTest {
	private val configuration = DailyAiSpringConfiguration()
	private val request = InterpretDailyMessageRequest(
		targetDate = LocalDate.parse("2026-07-30"),
		timezone = ZoneId.of("Europe/Rome"),
		text = "avena 40 g",
	)

	@Test
	fun `OpenAI SDK direct HTTP logging must remain disabled`() {
		requireOpenAiSdkLoggingDisabled(null)
		requireOpenAiSdkLoggingDisabled("off")
		requireOpenAiSdkLoggingDisabled(" OFF ")

		val exception = assertFailsWith<IllegalStateException> {
			requireOpenAiSdkLoggingDisabled("debug-private-provider-data")
		}
		assertFalse(exception.message.orEmpty().contains("debug-private-provider-data"))
	}

	@Test
	fun `missing OpenAI compatible API key produces unavailable interpreter and structured diagnostic`() {
		lateinit var configuredInterpreter: CaptureInterpreterPort
		val logEvents = LogEventCapture(DailyAiSpringConfiguration::class.java).use { logs ->
			configuredInterpreter = interpreter(
				environment = MockEnvironment().withProperty("spring.ai.openai.chat.model", "test-model"),
				chatModel = OptionsOnlyChatModel("default-model"),
			)
			logs.events
		}

		assertFailsWith<DailyAiConfigurationException> {
			configuredInterpreter.interpret(request)
		}
		val fields = logEvents.single().structuredFields()
		assertEquals("daily_ai_configuration_unavailable", fields["event"])
		assertEquals("failure", fields["outcome"])
		assertEquals("AI_API_KEY_MISSING", fields["errorCode"])
		assertEquals(false, fields["apiKeyConfigured"])
		assertEquals(true, fields["modelConfigured"])
		assertFalse(fields.containsKey("apiKey"))
		assertFalse(fields.containsKey("configuredModel"))
	}

	@Test
	fun `missing configured and effective model produces unavailable interpreter`() {
		val interpreter = interpreter(
			environment = MockEnvironment().withProperty("spring.ai.openai.api-key", "test-only-key"),
			chatModel = OptionsOnlyChatModel(null),
		)

		assertFailsWith<DailyAiConfigurationException> {
			interpreter.interpret(request)
		}
	}

	@Test
	fun `configured diagnostics identify model and bounded retry setting without revealing secrets`() {
		lateinit var configuredInterpreter: CaptureInterpreterPort
		val logEvents = LogEventCapture(DailyAiSpringConfiguration::class.java).use { logs ->
			configuredInterpreter = interpreter(
				environment = MockEnvironment()
					.withProperty("spring.ai.model.chat", "openai")
					.withProperty("spring.ai.openai.api-key", "secret-that-must-not-be-logged")
					.withProperty("spring.ai.openai.base-url", "https://user:password@openrouter.ai/api/v1?token=secret")
					.withProperty("fitlake.daily.ai.max-structured-output-retries", "2"),
				chatModel = OptionsOnlyChatModel("effective-model"),
			)
			logs.events
		}

		assertEquals("OPENAI_COMPATIBLE", configuredInterpreter.metadata.provider)
		assertEquals("effective-model", configuredInterpreter.metadata.model)
		assertEquals(DAILY_AI_PROMPT_VERSION, configuredInterpreter.metadata.promptVersion)
		val fields = logEvents.single().structuredFields()
		assertEquals("daily_ai_configured", fields["event"])
		assertEquals("success", fields["outcome"])
		assertEquals("OPENAI_COMPATIBLE", fields["provider"])
		assertEquals("effective-model", fields["model"])
		assertEquals(DAILY_AI_PROMPT_VERSION, fields["promptVersion"])
		assertEquals("openai", fields["chatMode"])
		assertEquals(true, fields["apiKeyConfigured"])
		assertEquals(true, fields["baseUrlConfigured"])
		assertEquals(4096, fields["maxOutputTokens"])
		assertEquals(2, fields["maxStructuredOutputRetries"])
		assertEquals(false, fields["nativeStructuredOutputEnabled"])
		assertFalse(fields.containsKey("apiKey"))
		assertFalse(fields.containsKey("baseUrl"))
		val renderedLogs = logEvents.renderedLogContent()
		assertFalse(renderedLogs.contains("secret-that-must-not-be-logged"))
		assertFalse(renderedLogs.contains("user:password"))
		assertFalse(renderedLogs.contains("token=secret"))
		assertTrue(renderedLogs.contains("effective-model"))
	}

	@Test
	fun `chat specific API key and model take precedence`() {
		val interpreter = interpreter(
			environment = MockEnvironment()
				.withProperty("spring.ai.openai.api-key", "common-test-key")
				.withProperty("spring.ai.openai.chat.api-key", "chat-test-key")
				.withProperty("spring.ai.openai.chat.model", "configured-model"),
			chatModel = OptionsOnlyChatModel("default-model"),
		)

		assertEquals("OPENAI_COMPATIBLE", interpreter.metadata.provider)
		assertEquals("configured-model", interpreter.metadata.model)
	}

	@Test
	fun `configured correction retry count controls the exact attempt bound`() {
		val chatModel = InvalidOutputChatModel("test-model")
		val interpreter = interpreter(
			environment = MockEnvironment()
				.withProperty("spring.ai.openai.api-key", "test-key")
				.withProperty("spring.ai.openai.chat.model", "test-model")
				.withProperty("fitlake.daily.ai.max-structured-output-retries", "2"),
			chatModel = chatModel,
		)

		assertFailsWith<DailyAiInvalidOutputException> {
			interpreter.interpret(request)
		}
		assertEquals(3, chatModel.callCount)
	}

	@Test
	fun `structured output correction defaults to one retry`() {
		val chatModel = InvalidOutputChatModel("test-model")
		val interpreter = interpreter(
			environment = MockEnvironment()
				.withProperty("spring.ai.openai.api-key", "test-key")
				.withProperty("spring.ai.openai.chat.model", "test-model"),
			chatModel = chatModel,
		)

		assertFailsWith<DailyAiInvalidOutputException> {
			interpreter.interpret(request)
		}
		assertEquals(2, chatModel.callCount)
	}

	@Test
	fun `invalid correction retry configuration fails fast`() {
		assertFailsWith<IllegalStateException> {
			interpreter(
				environment = MockEnvironment()
					.withProperty("spring.ai.openai.api-key", "test-key")
					.withProperty("spring.ai.openai.chat.model", "test-model")
					.withProperty("fitlake.daily.ai.max-structured-output-retries", "4"),
				chatModel = OptionsOnlyChatModel("test-model"),
			)
		}
	}

	private fun interpreter(environment: MockEnvironment, chatModel: ChatModel) =
		configuration.dailyAiInterpreter(
			chatModelProvider = provider(ChatModel::class.java, chatModel),
			resourceLoader = DefaultResourceLoader(),
			environment = environment,
		)

	private fun <T : Any> provider(type: Class<T>, bean: T): ObjectProvider<T> {
		val beanFactory = DefaultListableBeanFactory()
		beanFactory.registerSingleton(type.name, bean)
		return beanFactory.getBeanProvider(type)
	}

	private open class OptionsOnlyChatModel(defaultModel: String?) : ChatModel {
		private val options: ChatOptions = ChatOptions.builder()
			.apply { if (defaultModel != null) model(defaultModel) }
			.build()

		override fun getOptions(): ChatOptions = options

		override fun call(prompt: Prompt): ChatResponse = error("The configuration test must not call the model")
	}

	private class InvalidOutputChatModel(defaultModel: String?) : OptionsOnlyChatModel(defaultModel) {
		var callCount: Int = 0
			private set

		override fun call(prompt: Prompt): ChatResponse {
			callCount += 1
			return ChatResponse(listOf(Generation(AssistantMessage("not-json"))))
		}
	}
}
