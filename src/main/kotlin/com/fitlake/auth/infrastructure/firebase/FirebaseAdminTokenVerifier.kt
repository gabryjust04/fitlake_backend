package com.fitlake.auth.infrastructure.firebase

import com.fitlake.shared.application.elapsedMilliseconds
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthException
import org.slf4j.LoggerFactory

class FirebaseAdminTokenVerifier(
	private val firebaseAuth: FirebaseAuth,
) : FirebaseTokenVerifier {
	override fun verify(idToken: String): FirebaseTokenClaims {
		val startedAtNanos = System.nanoTime()
		if (idToken.isBlank()) {
			logRejected("BLANK_TOKEN", startedAtNanos)
			throw FirebaseTokenVerificationException()
		}

		return try {
			val token = firebaseAuth.verifyIdToken(idToken, false)
			FirebaseTokenClaims(
				issuer = token.issuer,
				subject = token.uid,
				email = token.email,
				emailVerified = token.isEmailVerified,
				displayName = token.name,
			)
		} catch (exception: FirebaseAuthException) {
			logRejected(exception.authErrorCode?.name ?: "UNKNOWN", startedAtNanos)
			throw FirebaseTokenVerificationException(exception)
		} catch (exception: IllegalArgumentException) {
			logRejected("MALFORMED_TOKEN", startedAtNanos)
			throw FirebaseTokenVerificationException(exception)
		}
	}

	private fun logRejected(errorCode: String, startedAtNanos: Long) {
		logger.atWarn()
			.addKeyValue("event", "auth_token_verification_failed")
			.addKeyValue("outcome", "rejected")
			.addKeyValue("authProvider", AUTH_PROVIDER)
			.addKeyValue("errorCode", errorCode)
			.addKeyValue("durationMs", elapsedMilliseconds(startedAtNanos))
			.log("Firebase token verification rejected")
	}

	companion object {
		private const val AUTH_PROVIDER = "firebase"
		private val logger = LoggerFactory.getLogger(FirebaseAdminTokenVerifier::class.java)
	}
}
