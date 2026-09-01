package com.fitlake.auth.application

import com.fitlake.user.domain.UserId

data class AuthenticatedUser(
	val userId: UserId,
	val externalSubject: String,
	val email: String?,
)
