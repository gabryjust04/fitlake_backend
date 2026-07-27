package com.fitlake.auth.infrastructure.firebase

import com.fitlake.auth.infrastructure.FirebaseAuthenticationToken
import com.fitlake.auth.infrastructure.RestAuthenticationEntryPoint
import com.fitlake.support.ImmediateTransactionExecutor
import com.fitlake.support.InMemoryUserAccountRepository
import com.fitlake.support.InMemoryUserAuthIdentityRepository
import com.fitlake.user.application.UserProvisioningService
import jakarta.servlet.FilterChain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.security.core.context.SecurityContextHolder
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FirebaseAuthenticationFilterTest {
	private val accounts = InMemoryUserAccountRepository()
	private val identities = InMemoryUserAuthIdentityRepository()
	private val claims = FirebaseTokenClaims(
		issuer = "https://securetoken.google.com/fitlake-test",
		subject = "firebase-uid",
		email = "user@example.com",
		emailVerified = true,
		displayName = "Andrea",
	)

	@AfterEach
	fun clearSecurityContext() {
		SecurityContextHolder.clearContext()
	}

	@Test
	fun `valid token provisions the user and authenticates the request`() {
		val request = requestWithToken("valid-token")
		val response = MockHttpServletResponse()
		var chainCalled = false
		val filter = filterWith { token ->
			assertEquals("valid-token", token)
			claims
		}

		filter.doFilter(request, response, FilterChain { _, _ -> chainCalled = true })

		assertTrue(chainCalled)
		assertEquals(1, accounts.count())
		assertEquals(1, identities.count())
		assertIs<FirebaseAuthenticationToken>(SecurityContextHolder.getContext().authentication)
	}

	@Test
	fun `invalid token returns a safe unauthorized response`() {
		val token = "secret-invalid-token"
		val response = MockHttpServletResponse()
		var chainCalled = false
		val filter = filterWith { throw FirebaseTokenVerificationException() }

		filter.doFilter(requestWithToken(token), response, FilterChain { _, _ -> chainCalled = true })

		assertFalse(chainCalled)
		assertEquals(401, response.status)
		assertEquals("{\"error\":\"unauthorized\"}", response.contentAsString)
		assertFalse(response.contentAsString.contains(token))
		assertNull(SecurityContextHolder.getContext().authentication)
	}

	@Test
	fun `expired token is rejected without exposing verifier details`() {
		val response = MockHttpServletResponse()
		val filter = filterWith {
			throw FirebaseTokenVerificationException(IllegalStateException("token expired at a private timestamp"))
		}

		filter.doFilter(requestWithToken("expired-token"), response, FilterChain { _, _ -> })

		assertEquals(401, response.status)
		assertEquals("{\"error\":\"unauthorized\"}", response.contentAsString)
		assertFalse(response.contentAsString.contains("expired", ignoreCase = true))
	}

	@Test
	fun `missing bearer token leaves the request unauthenticated for authorization`() {
		val request = MockHttpServletRequest()
		val response = MockHttpServletResponse()
		var chainCalled = false

		filterWith { claims }.doFilter(request, response, FilterChain { _, _ -> chainCalled = true })

		assertTrue(chainCalled)
		assertNull(SecurityContextHolder.getContext().authentication)
	}

	private fun filterWith(verifier: FirebaseTokenVerifier) = FirebaseAuthenticationFilter(
		tokenVerifier = verifier,
		userProvisioningService = UserProvisioningService(
			userAccountRepository = accounts,
			userAuthIdentityRepository = identities,
			transactionExecutor = ImmediateTransactionExecutor,
			clock = Clock.fixed(Instant.parse("2026-01-01T10:00:00Z"), ZoneId.of("UTC")),
			defaultUserTimezone = ZoneId.of("Europe/Rome"),
		),
		authenticationEntryPoint = RestAuthenticationEntryPoint(),
	)

	private fun requestWithToken(token: String) = MockHttpServletRequest().apply {
		addHeader("Authorization", "Bearer $token")
	}
}
