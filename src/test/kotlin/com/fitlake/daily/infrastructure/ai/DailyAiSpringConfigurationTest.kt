package com.fitlake.daily.infrastructure.ai

import com.fitlake.daily.application.ai.DailyAiConfigurationException
import com.fitlake.daily.application.ai.DailyAiProviderMetadata
import com.fitlake.daily.application.ai.DailyAiRequestContext
import com.fitlake.daily.application.ai.DailyAiTerminalService
import com.fitlake.daily.domain.inbox.DailyInboxEventId
import com.fitlake.user.domain.UserId
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.springframework.ai.chat.model.ChatModel
import org.springframework.ai.chat.model.ChatResponse
import org.springframework.ai.chat.prompt.ChatOptions
import org.springframework.ai.chat.prompt.Prompt
import org.springframework.ai.model.tool.ToolCallingManager
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.support.DefaultListableBeanFactory
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.core.io.DefaultResourceLoader
import org.springframework.mock.env.MockEnvironment
import org.junit.jupiter.api.extension.ExtendWith
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

@ExtendWith(OutputCaptureExtension::class)
class DailyAiSpringConfigurationTest {
	private val configuration = DailyAiSpringConfiguration()
	private val terminalService = mock(DailyAiTerminalService::class.java)
	private val toolCallingManager = ToolCallingManager.builder().build()

	@Test
	fun `missing OpenAI compatible API key produces unavailable interpreter and specific log`(
		output: CapturedOutput,
	) {
		val interpreter = interpreter(
			environment = MockEnvironment().withProperty("spring.ai.openai.chat.model", "test-model"),
			defaultModel = "default-model",
		)

		assertFailsWith<DailyAiConfigurationException> {
			interpreter.interpret(context(interpreter.metadata), "testo")
		}
		assertContains(output.all, "no usable OpenAI-compatible API key was found")
		assertContains(output.all, "configuredModel=test-model")
	}

	@Test
	fun `missing configured and effective model produces unavailable interpreter`() {
		val interpreter = interpreter(
			environment = MockEnvironment().withProperty("spring.ai.openai.api-key", "test-only-key"),
			defaultModel = null,
		)

		assertFailsWith<DailyAiConfigurationException> {
			interpreter.interpret(context(interpreter.metadata), "testo")
		}
	}

	@Test
	fun `configured diagnostics identify the model without revealing the API key`(
		output: CapturedOutput,
	) {
		val interpreter = interpreter(
			environment = MockEnvironment()
				.withProperty("spring.ai.model.chat", "openai")
				.withProperty("spring.ai.openai.api-key", "secret-that-must-not-be-logged")
				.withProperty("spring.ai.openai.base-url", "https://user:password@openrouter.ai/api/v1?token=secret"),
			defaultModel = "effective-model",
		)

		assertEquals("OPENAI_COMPATIBLE", interpreter.metadata.provider)
		assertEquals("effective-model", interpreter.metadata.model)
		assertEquals(DAILY_AI_PROMPT_VERSION, interpreter.metadata.promptVersion)
		assertContains(output.all, "Daily AI configured: provider=OPENAI_COMPATIBLE, model=effective-model")
		assertContains(output.all, "baseUrl=https://openrouter.ai")
		assertFalse(output.all.contains("secret-that-must-not-be-logged"))
		assertFalse(output.all.contains("user:password"))
		assertFalse(output.all.contains("token=secret"))
	}

	@Test
	fun `chat specific API key and model take precedence`() {
		val interpreter = interpreter(
			environment = MockEnvironment()
				.withProperty("spring.ai.openai.api-key", "common-test-key")
				.withProperty("spring.ai.openai.chat.api-key", "chat-test-key")
				.withProperty("spring.ai.openai.chat.model", "configured-model"),
			defaultModel = "default-model",
		)

		assertEquals("OPENAI_COMPATIBLE", interpreter.metadata.provider)
		assertEquals("configured-model", interpreter.metadata.model)
	}

	private fun interpreter(
		environment: MockEnvironment,
		defaultModel: String?,
	) = configuration.dailyAiInterpreter(
		chatModelProvider = provider(ChatModel::class.java, OptionsOnlyChatModel(defaultModel)),
		toolCallingManagerProvider = provider(ToolCallingManager::class.java, toolCallingManager),
		terminalServiceProvider = provider(DailyAiTerminalService::class.java, terminalService),
		resourceLoader = DefaultResourceLoader(),
		environment = environment,
	)

	private fun <T : Any> provider(type: Class<T>, bean: T): ObjectProvider<T> {
		val beanFactory = DefaultListableBeanFactory()
		beanFactory.registerSingleton(type.name, bean)
		return beanFactory.getBeanProvider(type)
	}

	private fun context(metadata: DailyAiProviderMetadata) = DailyAiRequestContext(
		inboxEventId = DailyInboxEventId(UUID.fromString("10000000-0000-0000-0000-000000000001")),
		userId = UserId(UUID.fromString("20000000-0000-0000-0000-000000000002")),
		date = LocalDate.parse("2026-07-30"),
		timezone = ZoneId.of("Europe/Rome"),
		replacesCaptureId = null,
		metadata = metadata,
		startedAt = Instant.parse("2026-07-30T08:00:00Z"),
		processingAttemptId = UUID.fromString("40000000-0000-0000-0000-000000000004"),
	)

	private class OptionsOnlyChatModel(defaultModel: String?) : ChatModel {
		private val options: ChatOptions = ChatOptions.builder()
			.apply { if (defaultModel != null) model(defaultModel) }
			.build()

		override fun getOptions(): ChatOptions = options

		override fun call(prompt: Prompt): ChatResponse = error("The configuration test must not call the model")
	}
}
