package com.fitlake.daily.infrastructure.ai

import com.fitlake.daily.application.ai.DailyAiConfigurationException
import com.fitlake.daily.application.ai.DailyAiInterpreter
import com.fitlake.daily.application.ai.DailyAiProviderMetadata
import com.fitlake.daily.application.ai.DailyAiRequestContext
import com.fitlake.daily.application.ai.DailyAiTerminalService
import com.fitlake.daily.domain.inbox.DailyInboxEventId
import com.fitlake.user.domain.UserId
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.springframework.ai.chat.model.ChatModel
import org.springframework.ai.openai.OpenAiChatOptions
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.SpringBootConfiguration
import org.springframework.boot.autoconfigure.EnableAutoConfiguration
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import kotlin.test.assertFailsWith
import kotlin.test.assertEquals
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
	private val interpreter: DailyAiInterpreter,
	private val chatModels: ObjectProvider<ChatModel>,
) {
	@Test
	fun `enabled OpenAI auto configuration with missing key starts and returns controlled configuration error`() {
		val chatModel = assertNotNull(chatModels.ifUnique)
		val options = chatModel.options as OpenAiChatOptions
		assertEquals(4096, options.maxTokens)
		assertFailsWith<DailyAiConfigurationException> {
			interpreter.interpret(context(interpreter.metadata), "avena 40 g")
		}
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

	@SpringBootConfiguration
	@EnableAutoConfiguration(
		excludeName = [
			"org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration",
			"org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration",
			"org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration",
		],
	)
	@Import(DailyAiSpringConfiguration::class, TestBeans::class)
	class TestApplication

	@TestConfiguration(proxyBeanMethods = false)
	class TestBeans {
		@Bean
		fun terminalService(): DailyAiTerminalService = mock(DailyAiTerminalService::class.java)
	}
}
