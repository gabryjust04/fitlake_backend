package com.fitlake.auth.infrastructure.firebase

import ch.qos.logback.classic.Level
import com.fitlake.support.LogEventCapture
import com.fitlake.support.renderedLogContent
import com.fitlake.support.structuredFields
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertContentEquals

class FirebaseAdminConfigTest {
	@Test
	fun `startup failure logs a sanitized stack and propagates only a safe exception`() {
		val privateCredentialDetail = "PRIVATE_CREDENTIAL_FILE_PATH_AND_JSON"
		val sourceFailure = IllegalStateException(privateCredentialDetail)
		val config = FirebaseAdminConfig()

		lateinit var propagated: FirebaseAdminInitializationException
		val events = LogEventCapture(FirebaseAdminConfig::class.java).use { logs ->
			propagated = assertFailsWith {
				config.initializationFailure(
					"APPLICATION_DEFAULT_CREDENTIALS_UNAVAILABLE",
					sourceFailure,
					System.nanoTime(),
				)
			}
			logs.events
		}

		val event = events.single()
		val fields = event.structuredFields()
		assertEquals(Level.ERROR, event.level)
		assertEquals("firebase_admin_initialization_failed", fields["event"])
		assertEquals("failure", fields["outcome"])
		assertEquals("APPLICATION_DEFAULT_CREDENTIALS_UNAVAILABLE", fields["errorCode"])
		assertEquals("IllegalStateException", fields["exceptionType"])
		assertNull(event.throwableProxy)
		assertFalse(events.renderedLogContent().contains(privateCredentialDetail))
		assertFalse(propagated.message.orEmpty().contains(privateCredentialDetail))
		assertNull(propagated.cause)
		assertContentEquals(sourceFailure.stackTrace, propagated.stackTrace)
	}
}
