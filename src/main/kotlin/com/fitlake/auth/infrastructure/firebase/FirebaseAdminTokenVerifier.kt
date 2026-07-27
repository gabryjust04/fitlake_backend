package com.fitlake.auth.infrastructure.firebase

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthException
import org.slf4j.LoggerFactory

class FirebaseAdminTokenVerifier(
	private val firebaseAuth: FirebaseAuth,
) : FirebaseTokenVerifier {
	override fun verify(idToken: String): FirebaseTokenClaims {
		if (idToken.isBlank()) {
			logger.warn("Firebase ID token rejected: reason=blank_token")
			throw FirebaseTokenVerificationException()
		}

		return try {
			val token = firebaseAuth.verifyIdToken(idToken, false)
			logger.debug(
				"Firebase ID token verified: issuer={}, emailPresent={}, emailVerified={}",
				token.issuer,
				token.email != null,
				token.isEmailVerified,
			)
			FirebaseTokenClaims(
				issuer = token.issuer,
				subject = token.uid,
				email = token.email,
				emailVerified = token.isEmailVerified,
				displayName = token.name,
			)
		} catch (exception: FirebaseAuthException) {
			logger.warn(
				"Firebase ID token rejected: authErrorCode={}, reason={}",
				exception.authErrorCode?.name ?: "UNKNOWN",
				safeReason(exception, idToken),
			)
			throw FirebaseTokenVerificationException(exception)
		} catch (exception: IllegalArgumentException) {
			logger.warn("Firebase ID token rejected: reason=malformed_token")
			throw FirebaseTokenVerificationException(exception)
		}
	}

	private fun safeReason(exception: FirebaseAuthException, idToken: String): String =
		exception.message
			?.replace(idToken, REDACTED_TOKEN)
			?.replace(Regex("[\\r\\n\\t]+"), " ")
			?.take(MAX_LOG_REASON_LENGTH)
			?.takeIf(String::isNotBlank)
			?: "not_available"

	companion object {
		private const val REDACTED_TOKEN = "[REDACTED]"
		private const val MAX_LOG_REASON_LENGTH = 500
		private val logger = LoggerFactory.getLogger(FirebaseAdminTokenVerifier::class.java)
	}
}
