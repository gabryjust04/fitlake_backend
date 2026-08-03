package com.fitlake.daily.infrastructure.ai

import com.fitlake.daily.application.ai.CaptureInterpreterPort
import com.fitlake.daily.application.ai.DailyAiConfigurationException
import com.fitlake.daily.application.ai.DailyAiProviderMetadata
import com.fitlake.daily.application.ai.InterpretDailyMessageRequest
import com.fitlake.daily.application.ai.InterpretedDailyMessage
import com.fitlake.shared.logging.sanitizedForTechnicalLogging
import org.slf4j.LoggerFactory
import org.springframework.ai.chat.model.ChatModel
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.env.Environment
import org.springframework.core.io.ResourceLoader
import java.io.IOException
import java.nio.charset.StandardCharsets

@Configuration(proxyBeanMethods = false)
class DailyAiSpringConfiguration {
	@Bean
	fun openAiSdkLoggingSafety(): OpenAiSdkLoggingSafety {
		requireOpenAiSdkLoggingDisabled(System.getenv(OPENAI_SDK_LOG_LEVEL_ENV))
		return OpenAiSdkLoggingSafety()
	}

	@Bean
	@ConditionalOnMissingBean(CaptureInterpreterPort::class)
	fun dailyAiInterpreter(
		chatModelProvider: ObjectProvider<ChatModel>,
		resourceLoader: ResourceLoader,
		environment: Environment,
	): CaptureInterpreterPort {
		val chatModel = chatModelProvider.ifUnique
		val apiKeyPresent = environment.firstConfiguredProperty(
			"spring.ai.openai.chat.api-key",
			"spring.ai.openai.api-key",
		) != null
		val configuredModel = environment.firstConfiguredProperty("spring.ai.openai.chat.model")
		val maxOutputTokens = environment.dailyAiMaxOutputTokens()
		val maxCorrectionRetries = environment.dailyAiMaxStructuredOutputRetries()
		val nativeStructuredOutputEnabled = environment.dailyAiNativeStructuredOutputEnabled()
		val baseUrlConfigured = environment.firstConfiguredProperty(
			"spring.ai.openai.chat.base-url",
			"spring.ai.openai.base-url",
		) != null

		if (chatModel == null) {
			logger.atWarn()
				.addKeyValue("event", "daily_ai_configuration_unavailable")
				.addKeyValue("outcome", "failure")
				.addKeyValue("errorCode", "AI_CHAT_MODEL_UNAVAILABLE")
				.addKeyValue("reason", chatModelProvider.unavailableDescription("ChatModel"))
				.addKeyValue("chatMode", environment.propertyDisplayValue("spring.ai.model.chat"))
				.addKeyValue("apiKeyConfigured", apiKeyPresent)
				.addKeyValue("modelConfigured", configuredModel != null)
				.log("Daily AI configuration is unavailable")
			return UnavailableCaptureInterpreter()
		}
		if (!apiKeyPresent) {
			logger.atWarn()
				.addKeyValue("event", "daily_ai_configuration_unavailable")
				.addKeyValue("outcome", "failure")
				.addKeyValue("errorCode", "AI_API_KEY_MISSING")
				.addKeyValue("chatMode", environment.propertyDisplayValue("spring.ai.model.chat"))
				.addKeyValue("apiKeyConfigured", false)
				.addKeyValue("modelConfigured", configuredModel != null)
				.log("Daily AI configuration is unavailable")
			return UnavailableCaptureInterpreter()
		}

		val systemPrompt = try {
			resourceLoader.getResource(DAILY_AI_PROMPT_RESOURCE).getContentAsString(StandardCharsets.UTF_8)
		} catch (exception: IOException) {
			logger.atError()
				.addKeyValue("event", "daily_ai_configuration_unavailable")
				.addKeyValue("outcome", "failure")
				.addKeyValue("errorCode", "AI_PROMPT_UNREADABLE")
				.addKeyValue("promptVersion", DAILY_AI_PROMPT_VERSION)
				.addKeyValue("exceptionType", exception.javaClass.name)
				.setCause(exception.sanitizedForTechnicalLogging())
				.log("Daily AI system prompt could not be read")
			return UnavailableCaptureInterpreter(exception)
		}
		val model = configuredModel
			?: runCatching { chatModel.options.model }.getOrNull().configuredValue()
			?: run {
				logger.atWarn()
					.addKeyValue("event", "daily_ai_configuration_unavailable")
					.addKeyValue("outcome", "failure")
					.addKeyValue("errorCode", "AI_MODEL_MISSING")
					.addKeyValue("chatMode", environment.propertyDisplayValue("spring.ai.model.chat"))
					.addKeyValue("apiKeyConfigured", apiKeyPresent)
					.log("Daily AI configuration is unavailable")
				return UnavailableCaptureInterpreter()
			}

		logger.atInfo()
			.addKeyValue("event", "daily_ai_configured")
			.addKeyValue("outcome", "success")
			.addKeyValue("provider", "OPENAI_COMPATIBLE")
			.addKeyValue("model", model)
			.addKeyValue("promptVersion", DAILY_AI_PROMPT_VERSION)
			.addKeyValue("chatMode", environment.propertyDisplayValue("spring.ai.model.chat"))
			.addKeyValue("apiKeyConfigured", true)
			.addKeyValue("baseUrlConfigured", baseUrlConfigured)
			.addKeyValue("maxOutputTokens", maxOutputTokens)
			.addKeyValue("maxStructuredOutputRetries", maxCorrectionRetries)
			.addKeyValue("nativeStructuredOutputEnabled", nativeStructuredOutputEnabled)
			.log("Daily AI configured")

		return SpringAiDailyAiInterpreter(
			chatModel = chatModel,
			systemPrompt = systemPrompt,
			metadata = DailyAiProviderMetadata(
				provider = "OPENAI_COMPATIBLE",
				model = model,
				promptVersion = DAILY_AI_PROMPT_VERSION,
			),
			maxOutputTokens = maxOutputTokens,
			maxCorrectionRetries = maxCorrectionRetries,
			nativeStructuredOutputEnabled = nativeStructuredOutputEnabled,
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

	private fun Environment.dailyAiMaxStructuredOutputRetries(): Int {
		val configured = runCatching { getProperty(DAILY_AI_STRUCTURED_OUTPUT_RETRIES_PROPERTY) }
			.getOrNull()
			.configuredValue()
			?: return DEFAULT_DAILY_AI_MAX_CORRECTION_RETRIES
		return configured.toIntOrNull()
			?.takeIf { it in 0..MAX_DAILY_AI_MAX_CORRECTION_RETRIES }
			?: throw IllegalStateException(
				"$DAILY_AI_STRUCTURED_OUTPUT_RETRIES_PROPERTY must be an integer between 0 and " +
					MAX_DAILY_AI_MAX_CORRECTION_RETRIES,
			)
	}

	private fun Environment.dailyAiNativeStructuredOutputEnabled(): Boolean {
		val configured = runCatching { getProperty(DAILY_AI_NATIVE_STRUCTURED_OUTPUT_PROPERTY) }
			.getOrNull()
			.configuredValue()
			?: return false
		return configured.toBooleanStrictOrNull()
			?: throw IllegalStateException("$DAILY_AI_NATIVE_STRUCTURED_OUTPUT_PROPERTY must be true or false")
	}

	private fun <T : Any> ObjectProvider<T>.unavailableDescription(componentName: String): String {
		val candidateCount = runCatching { stream().count() }.getOrNull()
		return when (candidateCount) {
			0L -> "$componentName bean missing"
			null -> "$componentName bean unavailable"
			else -> "$componentName bean is not unique (candidates=$candidateCount)"
		}
	}

	companion object {
		private const val NOT_CONFIGURED = "<not configured>"
		private const val OPENAI_SDK_LOG_LEVEL_ENV = "OPENAI_LOG"
		private const val DAILY_AI_STRUCTURED_OUTPUT_RETRIES_PROPERTY =
			"fitlake.daily.ai.max-structured-output-retries"
		private const val DAILY_AI_NATIVE_STRUCTURED_OUTPUT_PROPERTY =
			"fitlake.daily.ai.native-structured-output-enabled"
		private val logger = LoggerFactory.getLogger(DailyAiSpringConfiguration::class.java)
	}
}

class OpenAiSdkLoggingSafety internal constructor()

internal fun requireOpenAiSdkLoggingDisabled(configuredLevel: String?) {
	val normalizedLevel = configuredLevel?.trim()
	check(normalizedLevel.isNullOrEmpty() || normalizedLevel.equals("off", ignoreCase = true)) {
		"OPENAI_LOG must remain off because SDK HTTP logging can expose private request and response data"
	}
}

private class UnavailableCaptureInterpreter(
	private val cause: Throwable? = null,
) : CaptureInterpreterPort {
	override val metadata = DailyAiProviderMetadata(
		provider = "UNAVAILABLE",
		model = "unavailable",
		promptVersion = DAILY_AI_PROMPT_VERSION,
	)

	override fun interpret(request: InterpretDailyMessageRequest): InterpretedDailyMessage {
		throw DailyAiConfigurationException(cause)
	}
}
