package com.fitlake.auth.infrastructure

import com.fitlake.auth.application.AuthenticatedUser
import com.fitlake.user.domain.UserId
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException
import org.springframework.security.core.context.SecurityContextHolder
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class SecurityCurrentUserProviderTest {
	private val provider = SecurityCurrentUserProvider()

	@AfterEach
	fun clearSecurityContext() {
		SecurityContextHolder.clearContext()
	}

	@Test
	fun `requires an authenticated FitLake user`() {
		assertFailsWith<AuthenticationCredentialsNotFoundException> {
			provider.requireCurrentUser()
		}
	}

	@Test
	fun `returns the internal user from Firebase authentication`() {
		val expected = AuthenticatedUser(
			userId = UserId(UUID.randomUUID()),
			externalSubject = "firebase-uid",
			email = "user@example.com",
		)
		SecurityContextHolder.getContext().authentication =
			FirebaseAuthenticationToken.authenticated(expected)

		assertEquals(expected, provider.requireCurrentUser())
	}
}
