package com.fitlake.auth.infrastructure.firebase

import com.google.firebase.ErrorCode
import com.google.firebase.auth.AuthErrorCode
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthException
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mockito.doThrow
import org.mockito.Mockito.mock
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import kotlin.test.assertContains
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

@ExtendWith(OutputCaptureExtension::class)
class FirebaseAdminTokenVerifierTest {
	@Test
	fun `verification failure logs a useful code without exposing the token`(output: CapturedOutput) {
		val idToken = "secret.firebase.id-token"
		val firebaseAuth = mock(FirebaseAuth::class.java)
		val firebaseException = FirebaseAuthException(
			ErrorCode.INVALID_ARGUMENT,
			"Firebase ID token $idToken has incorrect aud claim",
			null,
			null,
			AuthErrorCode.INVALID_ID_TOKEN,
		)
		doThrow(firebaseException)
			.`when`(firebaseAuth)
			.verifyIdToken(idToken, false)

		assertFailsWith<FirebaseTokenVerificationException> {
			FirebaseAdminTokenVerifier(firebaseAuth).verify(idToken)
		}

		assertContains(output.out, "authErrorCode=INVALID_ID_TOKEN")
		assertContains(output.out, "incorrect aud claim")
		assertContains(output.out, "[REDACTED]")
		assertFalse(output.out.contains(idToken))
	}
}
