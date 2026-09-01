package com.fitlake.daily.infrastructure.ai

import com.fitlake.daily.application.ai.CaptureInterpreterPort
import com.fitlake.daily.application.ai.DailyAiConfigurationException
import com.fitlake.daily.application.ai.InterpretDailyMessageRequest
import org.junit.jupiter.api.Test
import org.springframework.ai.chat.model.ChatModel
import org.springframework.ai.openai.OpenAiChatOptions
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.SpringBootConfiguration
import org.springframework.boot.autoconfigure.EnableAutoConfiguration
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull

@SpringBootTest(
	classes = [DailyAiMissingConfigurationIntegrationTest.TestApplication::class],
	properties = [
		"spring.main.web-application-type=none",
		"spring.ai.model.chat=openai",
		"spring.ai.openai.api-key=none",
		"spring.ai.openai.chat.model=test-model",
	],
)
class DailyAiMissingConfigurationIntegrationTest @Autowired constructor(
	private val interpreter: CaptureInterpreterPort,
	private val chatModels: ObjectProvider<ChatModel>,
) {
	@Test
	fun `enabled OpenAI auto configuration with missing key starts and returns controlled configuration error`() {
		val chatModel = assertNotNull(chatModels.ifUnique)
		val options = chatModel.options as OpenAiChatOptions
		assertEquals(4096, options.maxTokens)
		assertFailsWith<DailyAiConfigurationException> {
			interpreter.interpret(
				InterpretDailyMessageRequest(
					targetDate = LocalDate.parse("2026-07-30"),
					timezone = ZoneId.of("Europe/Rome"),
					text = "avena 40 g",
				),
			)
		}
	}

	@SpringBootConfiguration
	@EnableAutoConfiguration(
		excludeName = [
			"org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration",
			"org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration",
			"org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration",
		],
	)
	@Import(DailyAiSpringConfiguration::class)
	class TestApplication
}
