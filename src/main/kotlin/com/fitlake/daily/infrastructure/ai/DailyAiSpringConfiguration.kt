package com.fitlake.daily.infrastructure.ai

import com.fitlake.daily.application.ai.DailyAiConfigurationException
import com.fitlake.daily.application.ai.DailyAiInterpreter
import com.fitlake.daily.application.ai.DailyAiProviderMetadata
import com.fitlake.daily.application.ai.DailyAiRequestContext
import com.fitlake.daily.application.ai.DailyAiResult
import com.fitlake.daily.application.ai.DailyAiTerminalService
import org.springframework.ai.chat.model.ChatModel
import org.springframework.ai.model.tool.ToolCallingManager
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.env.Environment
import org.springframework.core.io.ResourceLoader
import org.slf4j.LoggerFactory
import java.io.IOException
import java.net.URI
import java.nio.charset.StandardCharsets

@Configuration(proxyBeanMethods = false)
class DailyAiSpringConfiguration {

	@Bean
	@ConditionalOnMissingBean(DailyAiInterpreter::class)
	fun dailyAiInterpreter(
		chatModelProvider: ObjectProvider<ChatModel>,
		toolCallingManagerProvider: ObjectProvider<ToolCallingManager>,
		terminalServiceProvider: ObjectProvider<DailyAiTerminalService>,
		resourceLoader: ResourceLoader,
		environment: Environment,
	): DailyAiInterpreter {
		val chatModel = chatModelProvider.ifUnique
		val toolCallingManager = toolCallingManagerProvider.ifUnique
		val terminalService = terminalServiceProvider.ifUnique
		val apiKeyPresent = environment.firstConfiguredProperty(
			"spring.ai.openai.chat.api-key",
			"spring.ai.openai.api-key",
		) != null
		val configuredModel = environment.firstConfiguredProperty("spring.ai.openai.chat.model")
		val maxOutputTokens = environment.dailyAiMaxOutputTokens()

		val unavailableComponents = buildList {
			if (chatModel == null) {
				add(chatModelProvider.unavailableDescription("ChatModel"))
			}
			if (toolCallingManager == null) {
				add(toolCallingManagerProvider.unavailableDescription("ToolCallingManager"))
			}
			if (terminalService == null) {
				add(terminalServiceProvider.unavailableDescription("DailyAiTerminalService"))
			}
		}
		if (unavailableComponents.isNotEmpty()) {
			logger.warn(
				"Daily AI is unavailable: {}. Configuration: spring.ai.model.chat={}, " +
					"apiKeyPresent={}, configuredModel={}",
				unavailableComponents.joinToString(),
				environment.propertyDisplayValue("spring.ai.model.chat"),
				apiKeyPresent,
				configuredModel ?: NOT_CONFIGURED,
			)
			return UnavailableDailyAiInterpreter()
		}
		val availableChatModel = requireNotNull(chatModel)
		val availableToolCallingManager = requireNotNull(toolCallingManager)
		val availableTerminalService = requireNotNull(terminalService)
		if (!apiKeyPresent) {
			logger.warn(
				"Daily AI is unavailable: no usable OpenAI-compatible API key was found. " +
					"Set OPENAI_API_KEY or spring.ai.openai.api-key; values that are blank, 'none', " +
					"or unresolved placeholders are treated as missing. Configuration: " +
					"spring.ai.model.chat={}, configuredModel={}",
				environment.propertyDisplayValue("spring.ai.model.chat"),
				configuredModel ?: NOT_CONFIGURED,
			)
			return UnavailableDailyAiInterpreter()
		}

		val systemPrompt = try {
			resourceLoader.getResource(DAILY_AI_PROMPT_RESOURCE).getContentAsString(StandardCharsets.UTF_8)
		} catch (exception: IOException) {
			logger.error(
				"Daily AI is unavailable: system prompt could not be read from {} (exceptionType={})",
				DAILY_AI_PROMPT_RESOURCE,
				exception.javaClass.simpleName,
				exception,
			)
			return UnavailableDailyAiInterpreter(exception)
		}
		val model = configuredModel
			?: runCatching { availableChatModel.options.model }.getOrNull().configuredValue()
			?: run {
				logger.warn(
					"Daily AI is unavailable: no usable chat model was configured. " +
						"Set SPRING_AI_OPENAI_CHAT_MODEL or spring.ai.openai.chat.model. " +
						"Configuration: spring.ai.model.chat={}, apiKeyPresent={}",
					environment.propertyDisplayValue("spring.ai.model.chat"),
					apiKeyPresent,
				)
				return UnavailableDailyAiInterpreter()
			}

		logger.info(
			"Daily AI configured: provider=OPENAI_COMPATIBLE, model={}, promptVersion={}, " +
				"spring.ai.model.chat={}, apiKeyPresent=true, baseUrl={}, maxTokens={}",
			model,
			DAILY_AI_PROMPT_VERSION,
			environment.propertyDisplayValue("spring.ai.model.chat"),
			environment.firstConfiguredProperty(
				"spring.ai.openai.chat.base-url",
				"spring.ai.openai.base-url",
			)?.safeEndpointDisplay() ?: NOT_CONFIGURED,
			maxOutputTokens,
		)

		return SpringAiDailyAiInterpreter(
			chatModel = availableChatModel,
			toolCallingManager = availableToolCallingManager,
			terminalService = availableTerminalService,
			systemPrompt = systemPrompt,
			metadata = DailyAiProviderMetadata(
				provider = "OPENAI_COMPATIBLE",
				model = model,
				promptVersion = DAILY_AI_PROMPT_VERSION,
			),
			maxOutputTokens = maxOutputTokens,
		)
	}

	private fun Environment.firstConfiguredProperty(vararg names: String): String? = names
		.asSequence()
		.mapNotNull { name -> runCatching { getProperty(name) }.getOrNull().configuredValue() }
		.firstOrNull()

	private fun String?.configuredValue(): String? = this
		?.trim()
		?.takeIf { value ->
			value.isNotEmpty() &&
				!value.equals("none", ignoreCase = true) &&
				!(value.startsWith("\${") && value.endsWith("}"))
		}

	private fun Environment.propertyDisplayValue(name: String): String =
		runCatching { getProperty(name) }
			.getOrNull()
			?.trim()
			?.takeIf(String::isNotEmpty)
			?: NOT_CONFIGURED

	private fun Environment.dailyAiMaxOutputTokens(): Int {
		val configured = runCatching { getProperty("spring.ai.openai.chat.max-tokens") }
			.getOrNull()
			.configuredValue()
			?: return DEFAULT_DAILY_AI_MAX_OUTPUT_TOKENS
		return configured.toIntOrNull()
			?.takeIf { it > 0 }
			?: throw IllegalStateException(
				"spring.ai.openai.chat.max-tokens must be a positive integer",
			)
	}

	private fun <T : Any> ObjectProvider<T>.unavailableDescription(componentName: String): String {
		val candidateCount = runCatching { stream().count() }.getOrNull()
		return when (candidateCount) {
			0L -> "$componentName bean missing"
			null -> "$componentName bean unavailable"
			else -> "$componentName bean is not unique (candidates=$candidateCount)"
		}
	}

	private fun String.safeEndpointDisplay(): String = runCatching {
		val uri = URI(this)
		require(uri.scheme != null && uri.host != null)
		URI(uri.scheme, null, uri.host, uri.port, uri.path, null, null).toString()
	}.getOrDefault("<configured but invalid URI>")

	companion object {
		private const val NOT_CONFIGURED = "<not configured>"
		private val logger = LoggerFactory.getLogger(DailyAiSpringConfiguration::class.java)
	}
}

private class UnavailableDailyAiInterpreter(
	private val cause: Throwable? = null,
) : DailyAiInterpreter {
	override val metadata = DailyAiProviderMetadata(
		provider = "UNAVAILABLE",
		model = "unavailable",
		promptVersion = DAILY_AI_PROMPT_VERSION,
	)

	override fun interpret(context: DailyAiRequestContext, text: String): DailyAiResult {
		throw DailyAiConfigurationException(cause)
	}
}
