package com.fitlake.auth.infrastructure.firebase

fun interface FirebaseTokenVerifier {
	fun verify(idToken: String): FirebaseTokenClaims
}
