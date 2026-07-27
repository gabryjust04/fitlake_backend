package com.fitlake.auth.infrastructure.firebase

class FirebaseTokenVerificationException(cause: Throwable? = null) : RuntimeException(
	"Firebase ID token verification failed",
	cause,
)
