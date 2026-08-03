package com.fitlake.user.application

import ch.qos.logback.classic.Level
import com.fitlake.shared.application.TransactionExecutor
import com.fitlake.support.ImmediateTransactionExecutor
import com.fitlake.support.InMemoryUserAccountRepository
import com.fitlake.support.InMemoryUserAuthIdentityRepository
import com.fitlake.support.LogEventCapture
import com.fitlake.support.structuredFields
import com.fitlake.user.domain.AuthProvider
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

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
		val account = LogEventCapture(UserProvisioningService::class.java).use { capture ->
			val provisioned = serviceAt(firstLogin).provision(command())
			val event = capture.events.single()
			val fields = event.structuredFields()
			assertEquals(Level.INFO, event.level)
			assertEquals("user_account_provisioned", fields["event"])
			assertEquals("success", fields["outcome"])
			assertEquals("firebase", fields["authProvider"])
			assertEquals(provisioned.userId.value, fields["userRef"])
			val rendered = event.formattedMessage + fields.entries.joinToString()
			assertFalse(rendered.contains(command().email!!))
			assertFalse(rendered.contains(command().externalSubject))
			assertFalse(rendered.contains(command().issuer))
			assertFalse(rendered.contains(command().displayName!!))
			provisioned
		}

		assertEquals(1, accounts.count())
		assertEquals(1, identities.count())
		assertEquals("user@example.com", account.email)
		assertEquals("Europe/Rome", account.timezone.id)
	}

	@Test
	fun `existing identity resolves the existing user`() {
		val first = serviceAt(firstLogin).provision(command())

		val repeated = LogEventCapture(UserProvisioningService::class.java).use { capture ->
			serviceAt(firstLogin.plusSeconds(60)).provision(command()).also {
				assertTrue(capture.events.isEmpty())
			}
		}

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

	@Test
	fun `failed transaction does not emit a provisioning success event`() {
		val failingCommit = object : TransactionExecutor {
			override fun <T : Any> required(action: () -> T): T {
				action()
				throw IllegalStateException("commit failed for private-user@example.com")
			}
		}

		LogEventCapture(UserProvisioningService::class.java).use { capture ->
			assertFailsWith<IllegalStateException> {
				serviceAt(firstLogin, failingCommit).provision(command())
			}
			assertTrue(capture.events.isEmpty())
		}
	}

	private fun serviceAt(
		now: Instant,
		transactionExecutor: TransactionExecutor = ImmediateTransactionExecutor,
	) = UserProvisioningService(
		userAccountRepository = accounts,
		userAuthIdentityRepository = identities,
		transactionExecutor = transactionExecutor,
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
