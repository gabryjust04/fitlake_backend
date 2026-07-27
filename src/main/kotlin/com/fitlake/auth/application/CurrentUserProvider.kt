package com.fitlake.auth.application

interface CurrentUserProvider {
	fun requireCurrentUser(): AuthenticatedUser
}
