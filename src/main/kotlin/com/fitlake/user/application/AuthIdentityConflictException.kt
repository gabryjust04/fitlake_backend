package com.fitlake.user.application

class AuthIdentityConflictException(cause: Throwable) : RuntimeException(
	"The external authentication identity already exists",
	cause,
)
