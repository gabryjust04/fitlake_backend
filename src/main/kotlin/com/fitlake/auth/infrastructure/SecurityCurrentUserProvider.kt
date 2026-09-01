package com.fitlake.auth.infrastructure

import com.fitlake.auth.application.AuthenticatedUser
import com.fitlake.auth.application.CurrentUserProvider
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Component

@Component
class SecurityCurrentUserProvider : CurrentUserProvider {
	override fun requireCurrentUser(): AuthenticatedUser {
		val authentication = SecurityContextHolder.getContext().authentication
		if (authentication !is FirebaseAuthenticationToken || !authentication.isAuthenticated) {
			throw AuthenticationCredentialsNotFoundException("Authenticated FitLake user is required")
		}
		return authentication.authenticatedUser
	}
}
