package com.fitlake.api

import org.junit.jupiter.api.Test
import java.util.Properties
import kotlin.test.assertEquals

class FitlakeApiApplicationTests {

	@Test
	fun `configuration externalizes secrets and delegates schema management to Flyway`() {
		val properties = Properties().apply {
			FitlakeApiApplicationTests::class.java
				.getResourceAsStream("/application.properties")
				.use { load(requireNotNull(it)) }
		}

		assertEquals("\${DATABASE_URL}", properties.getProperty("spring.datasource.url"))
		assertEquals("\${DATABASE_USERNAME}", properties.getProperty("spring.datasource.username"))
		assertEquals("\${DATABASE_PASSWORD}", properties.getProperty("spring.datasource.password"))
		assertEquals("\${OPENAI_API_KEY}", properties.getProperty("spring.ai.openai.api-key"))
		assertEquals("\${FIREBASE_PROJECT_ID}", properties.getProperty("fitlake.firebase.project-id"))
		assertEquals(
			"\${FITLAKE_DEFAULT_TIMEZONE:Europe/Rome}",
			properties.getProperty("fitlake.user.default-timezone"),
		)
		assertEquals("false", properties.getProperty("spring.docker.compose.enabled"))
		assertEquals("true", properties.getProperty("spring.flyway.enabled"))
		assertEquals("validate", properties.getProperty("spring.jpa.hibernate.ddl-auto"))
	}

}
