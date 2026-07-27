package com.fitlake.auth.infrastructure.firebase

data class FirebaseTokenClaims(
	val issuer: String,
	val subject: String,
	val email: String?,
	val emailVerified: Boolean,
	val displayName: String?,
)
