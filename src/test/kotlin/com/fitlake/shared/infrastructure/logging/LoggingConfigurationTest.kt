package com.fitlake.shared.infrastructure.logging

import org.junit.jupiter.api.Test
import org.springframework.core.io.ClassPathResource
import java.util.Properties
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LoggingConfigurationTest {
	@Test
	fun `common logging is privacy safe and actuator exposes health only`() {
		val properties = properties("application.properties")

		assertEquals("health", properties.getProperty("management.endpoints.web.exposure.include"))
		assertEquals("never", properties.getProperty("management.endpoint.health.show-details"))
		assertEquals("INFO", properties.getProperty("logging.level.root"))
		assertEquals("INFO", properties.getProperty("logging.level.com.fitlake"))
		assertEquals("OFF", properties.getProperty("logging.level.org.springframework.ai"))
		assertEquals(
			"OFF",
			properties.getProperty("logging.level.org.springframework.ai.retry.autoconfigure.SpringAiRetryAutoConfiguration"),
		)
		assertEquals("OFF", properties.getProperty("logging.level.org.springframework.ai.openai.OpenAiChatModel"))
		assertEquals("OFF", properties.getProperty("logging.level.org.springframework.web.servlet.PageNotFound"))
		assertEquals("WARN", properties.getProperty("logging.level.org.apache.coyote.http11.Http11Processor"))
		assertEquals("WARN", properties.getProperty("logging.level.org.apache.tomcat.util.http.parser.Cookie"))
		assertEquals("WARN", properties.getProperty("logging.level.org.hibernate.SQL"))
		assertEquals("OFF", properties.getProperty("logging.level.org.hibernate.orm.jdbc.bind"))
		assertEquals("OFF", properties.getProperty("logging.level.org.hibernate.orm.jdbc.error"))
		assertEquals("OFF", properties.getProperty("logging.level.org.hibernate.orm.jdbc.warn"))
		assertEquals("false", properties.getProperty("spring.jpa.show-sql"))
		assertTrue(properties.getProperty("logging.pattern.console").contains("%X{requestId:-}"))
		assertTrue(properties.getProperty("logging.pattern.console").contains("%kvp"))
	}

	@Test
	fun `production uses native ECS JSON on stdout without file logging`() {
		val properties = properties("application-prod.properties")

		assertEquals("ecs", properties.getProperty("logging.structured.format.console"))
		assertEquals("INFO", properties.getProperty("logging.level.root"))
		assertEquals("INFO", properties.getProperty("logging.level.com.fitlake"))
		assertFalse(properties.stringPropertyNames().any { it.startsWith("logging.file.") })
	}

	@Test
	fun `development and test profiles keep useful application logs without SQL bindings`() {
		val development = properties("application-dev.properties")
		val test = properties("application-test.properties")

		assertEquals("DEBUG", development.getProperty("logging.level.com.fitlake"))
		assertNull(development.getProperty("logging.structured.format.console"))
		assertEquals("WARN", test.getProperty("logging.level.root"))
		assertEquals("INFO", test.getProperty("logging.level.com.fitlake"))
		assertEquals("OFF", development.getProperty("logging.level.org.hibernate.orm.jdbc.bind"))
		assertEquals("OFF", test.getProperty("logging.level.org.hibernate.orm.jdbc.bind"))
	}

	@Test
	fun `custom Logback configuration is not present`() {
		assertFalse(ClassPathResource("logback.xml").exists())
		assertFalse(ClassPathResource("logback-spring.xml").exists())
	}

	private fun properties(resourceName: String): Properties {
		val resource = ClassPathResource(resourceName)
		assertTrue(resource.exists(), "$resourceName must exist")
		return Properties().also { properties ->
			resource.inputStream.use(properties::load)
		}
	}
}
