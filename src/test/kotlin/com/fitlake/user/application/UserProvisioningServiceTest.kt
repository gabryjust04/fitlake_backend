package com.fitlake.user.application

import com.fitlake.support.ImmediateTransactionExecutor
import com.fitlake.support.InMemoryUserAccountRepository
import com.fitlake.support.InMemoryUserAuthIdentityRepository
import com.fitlake.user.domain.AuthProvider
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class UserProvisioningServiceTest {
	private val accounts = InMemoryUserAccountRepository()
	private val identities = InMemoryUserAuthIdentityRepository()
	private val firstLogin = Instant.parse("2026-01-01T10:00:00Z")

	@BeforeEach
	fun reset() {
		accounts.clear()
		identities.clear()
	}

	@Test
	fun `first valid login provisions an internal user and identity`() {
		val account = serviceAt(firstLogin).provision(command())

		assertEquals(1, accounts.count())
		assertEquals(1, identities.count())
		assertEquals("user@example.com", account.email)
		assertEquals("Europe/Rome", account.timezone.id)
	}

	@Test
	fun `existing identity resolves the existing user`() {
		val first = serviceAt(firstLogin).provision(command())

		val repeated = serviceAt(firstLogin.plusSeconds(60)).provision(command())

		assertEquals(first.userId, repeated.userId)
		assertEquals(1, accounts.count())
		assertEquals(1, identities.count())
	}

	@Test
	fun `repeated login updates last login timestamp`() {
		serviceAt(firstLogin).provision(command())
		val later = firstLogin.plusSeconds(60)

		serviceAt(later).provision(command())

		val identity = identities.findByIssuerAndExternalSubject(command().issuer, command().externalSubject)
		assertEquals(later, identity?.lastLoginAt)
	}

	@Test
	fun `unique constraint race resolves the concurrently created identity`() {
		identities.conflictOnNextInsert = true

		val account = serviceAt(firstLogin).provision(command())

		assertEquals(account.userId, identities.findByIssuerAndExternalSubject(
			command().issuer,
			command().externalSubject,
		)?.userId)
		assertEquals(1, identities.count())
	}

	@Test
	fun `same email with a different Firebase subject creates a different user`() {
		val first = serviceAt(firstLogin).provision(command(externalSubject = "uid-1"))
		val second = serviceAt(firstLogin).provision(command(externalSubject = "uid-2"))

		assertNotEquals(first.userId, second.userId)
		assertEquals(2, accounts.count())
		assertEquals(2, identities.count())
	}

	@Test
	fun `unverified email is copied into the application profile`() {
		val account = serviceAt(firstLogin).provision(command(emailVerified = false))

		assertEquals("user@example.com", account.email)
		assertEquals(
			"user@example.com",
			identities.findByIssuerAndExternalSubject(command().issuer, command().externalSubject)?.emailAtLinkTime,
		)
	}

	@Test
	fun `repeated login synchronizes a changed email regardless of verification`() {
		serviceAt(firstLogin).provision(command(email = "old@example.com"))
		val later = firstLogin.plusSeconds(60)

		val updated = serviceAt(later).provision(
			command(email = "new@example.com", emailVerified = false),
		)

		assertEquals("new@example.com", updated.email)
		assertEquals(later, updated.updatedAt)
		assertEquals(
			"old@example.com",
			identities.findByIssuerAndExternalSubject(command().issuer, command().externalSubject)?.emailAtLinkTime,
		)
	}

	@Test
	fun `missing provider email does not erase the existing email`() {
		serviceAt(firstLogin).provision(command(email = "stored@example.com"))

		val updated = serviceAt(firstLogin.plusSeconds(60)).provision(command(email = null))

		assertEquals("stored@example.com", updated.email)
		assertEquals(firstLogin, updated.updatedAt)
	}

	private fun serviceAt(now: Instant) = UserProvisioningService(
		userAccountRepository = accounts,
		userAuthIdentityRepository = identities,
		transactionExecutor = ImmediateTransactionExecutor,
		clock = Clock.fixed(now, ZoneId.of("UTC")),
		defaultUserTimezone = ZoneId.of("Europe/Rome"),
	)

	private fun command(
		externalSubject: String = "firebase-uid",
		email: String? = "user@example.com",
		emailVerified: Boolean = true,
	) = ProvisionUserCommand(
		provider = AuthProvider.FIREBASE,
		issuer = "https://securetoken.google.com/fitlake-dev",
		externalSubject = externalSubject,
		email = email,
		emailVerified = emailVerified,
		displayName = "Andrea",
	)
}
