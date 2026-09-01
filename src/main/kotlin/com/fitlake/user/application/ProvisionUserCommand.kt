package com.fitlake.user.application

import com.fitlake.user.domain.AuthProvider

data class ProvisionUserCommand(
	val provider: AuthProvider,
	val issuer: String,
	val externalSubject: String,
	val email: String?,
	val emailVerified: Boolean,
	val displayName: String?,
)
