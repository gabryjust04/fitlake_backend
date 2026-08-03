package com.fitlake.auth.infrastructure.firebase

import ch.qos.logback.classic.Level
import com.fitlake.auth.infrastructure.FirebaseAuthenticationToken
import com.fitlake.auth.infrastructure.RestAuthenticationEntryPoint
import com.fitlake.support.ImmediateTransactionExecutor
import com.fitlake.support.InMemoryUserAccountRepository
import com.fitlake.support.InMemoryUserAuthIdentityRepository
import com.fitlake.support.LogEventCapture
import com.fitlake.support.renderedLogContent
import com.fitlake.support.structuredFields
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
import kotlin.test.assertFailsWith
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
	fun `downstream application exceptions are not reclassified as authentication failures`() {
		val response = MockHttpServletResponse()
		val filter = filterWith { claims }

		val exception = assertFailsWith<IllegalArgumentException> {
			filter.doFilter(
				requestWithToken("valid-token"),
				response,
				FilterChain { _, _ -> throw IllegalArgumentException("downstream validation failed") },
			)
		}

		assertEquals("downstream validation failed", exception.message)
		assertEquals(200, response.status)
		assertEquals("", response.contentAsString)
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

	@Test
	fun `malformed bearer token logs structured metadata without token or raw path`() {
		val token = "secret token value"
		val rawPath = "/api/daily/captures/private-resource-id"
		val request = requestWithToken(token).apply {
			requestURI = rawPath
		}
		val response = MockHttpServletResponse()

		LogEventCapture(FirebaseAuthenticationFilter::class.java).use { capture ->
			filterWith { claims }.doFilter(request, response, FilterChain { _, _ -> })

			val event = capture.events.single()
			val fields = event.structuredFields()
			assertEquals(Level.WARN, event.level)
			assertEquals("auth_token_verification_failed", fields["event"])
			assertEquals("rejected", fields["outcome"])
			assertEquals("firebase", fields["authProvider"])
			assertEquals("MALFORMED_BEARER_TOKEN", fields["errorCode"])
			assertEquals("GET", fields["method"])
			val rendered = event.formattedMessage + fields.entries.joinToString()
			assertFalse(rendered.contains(token))
			assertFalse(rendered.contains(rawPath))
		}

		assertEquals(401, response.status)
	}

	@Test
	fun `successful authentication debug event contains only internal user reference`() {
		val request = requestWithToken("private-valid-token")
		val response = MockHttpServletResponse()

		LogEventCapture(FirebaseAuthenticationFilter::class.java, Level.DEBUG).use { capture ->
			filterWith { claims }.doFilter(request, response, FilterChain { _, _ -> })

			val event = capture.events.single()
			val fields = event.structuredFields()
			assertEquals(Level.DEBUG, event.level)
			assertEquals("auth_user_resolved", fields["event"])
			assertEquals("success", fields["outcome"])
			assertEquals("firebase", fields["authProvider"])
			val internalUserId = identities.findByIssuerAndExternalSubject(claims.issuer, claims.subject)?.userId?.value
			assertEquals(internalUserId, fields["userRef"])
			val rendered = event.formattedMessage + fields.entries.joinToString()
			assertFalse(rendered.contains("private-valid-token"))
			assertFalse(rendered.contains(claims.email!!))
			assertFalse(rendered.contains(claims.subject))
			assertFalse(rendered.contains(claims.issuer))
		}
	}

	@Test
	fun `unexpected authentication processing failure is logged once without raw details`() {
		val privateFailure = "database detail containing a private account value"
		val response = MockHttpServletResponse()
		val filter = filterWith { throw IllegalStateException(privateFailure) }

		LogEventCapture(FirebaseAuthenticationFilter::class.java).use { capture ->
			filter.doFilter(requestWithToken("private-valid-token"), response, FilterChain { _, _ -> })

			val event = capture.events.single()
			val fields = event.structuredFields()
			assertEquals(Level.ERROR, event.level)
			assertEquals("auth_user_resolution_failed", fields["event"])
			assertEquals("failure", fields["outcome"])
			assertEquals("AUTHENTICATION_PROCESSING_FAILED", fields["errorCode"])
			assertEquals("IllegalStateException", fields["exceptionType"])
			assertFalse(capture.events.renderedLogContent().contains(privateFailure))
		}

		assertEquals(500, response.status)
		assertEquals("{\"error\":\"internal_server_error\"}", response.contentAsString)
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

	private fun requestWithToken(token: String) = MockHttpServletRequest("GET", "/api/test").apply {
		addHeader("Authorization", "Bearer $token")
	}
}
