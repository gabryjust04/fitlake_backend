package com.fitlake.auth.infrastructure.firebase

import ch.qos.logback.classic.Level
import com.fitlake.support.LogEventCapture
import com.fitlake.support.structuredFields
import com.google.firebase.ErrorCode
import com.google.firebase.auth.AuthErrorCode
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthException
import org.junit.jupiter.api.Test
import org.mockito.Mockito.doThrow
import org.mockito.Mockito.mock
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull

class FirebaseAdminTokenVerifierTest {
	@Test
	fun `verification failure logs structured metadata without provider message or token`() {
		val idToken = "secret.firebase.id-token"
		val providerMessage = "private-user@example.com used $idToken with an incorrect aud claim"
		val firebaseAuth = mock(FirebaseAuth::class.java)
		val firebaseException = FirebaseAuthException(
			ErrorCode.INVALID_ARGUMENT,
			providerMessage,
			null,
			null,
			AuthErrorCode.INVALID_ID_TOKEN,
		)
		doThrow(firebaseException)
			.`when`(firebaseAuth)
			.verifyIdToken(idToken, false)

		LogEventCapture(FirebaseAdminTokenVerifier::class.java).use { capture ->
			assertFailsWith<FirebaseTokenVerificationException> {
				FirebaseAdminTokenVerifier(firebaseAuth).verify(idToken)
			}

			val event = capture.events.single()
			val fields = event.structuredFields()
			assertEquals(Level.WARN, event.level)
			assertEquals("auth_token_verification_failed", fields["event"])
			assertEquals("rejected", fields["outcome"])
			assertEquals("firebase", fields["authProvider"])
			assertEquals("INVALID_ID_TOKEN", fields["errorCode"])
			assertEquals("Firebase token verification rejected", event.formattedMessage)
			assertNull(event.throwableProxy)
			val rendered = event.formattedMessage + fields.entries.joinToString()
			assertFalse(rendered.contains(idToken))
			assertFalse(rendered.contains(providerMessage))
			assertFalse(rendered.contains("private-user@example.com"))
		}
	}
}
