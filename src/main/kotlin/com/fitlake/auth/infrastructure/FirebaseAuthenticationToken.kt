package com.fitlake.auth.infrastructure

import com.fitlake.auth.application.AuthenticatedUser
import org.springframework.security.authentication.AbstractAuthenticationToken
import org.springframework.security.core.GrantedAuthority

class FirebaseAuthenticationToken private constructor(
	val authenticatedUser: AuthenticatedUser,
	authorities: Collection<GrantedAuthority>,
) : AbstractAuthenticationToken(authorities) {
	init {
		super.setAuthenticated(true)
	}

	override fun getCredentials(): Any? = null

	override fun getPrincipal(): Any = authenticatedUser.userId

	override fun getName(): String = authenticatedUser.userId.toString()

	override fun setAuthenticated(isAuthenticated: Boolean) {
		if (isAuthenticated) {
			throw IllegalArgumentException("Use the authenticated factory method")
		}
		super.setAuthenticated(false)
	}

	companion object {
		fun authenticated(
			authenticatedUser: AuthenticatedUser,
			authorities: Collection<GrantedAuthority> = emptyList(),
		): FirebaseAuthenticationToken = FirebaseAuthenticationToken(authenticatedUser, authorities)
	}
}
