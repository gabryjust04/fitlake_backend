package com.fitlake.user.domain

import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class UserAuthIdentityTest {
	private val createdAt = Instant.parse("2026-01-01T10:00:00Z")

	@Test
	fun `records a later login without changing identity`() {
		val identity = identity()
		val nextLogin = createdAt.plusSeconds(60)

		val updated = identity.recordLogin(nextLogin)

		assertEquals(identity.identityId, updated.identityId)
		assertEquals(nextLogin, updated.lastLoginAt)
	}

	@Test
	fun `does not move last login backwards`() {
		val identity = identity().recordLogin(createdAt.plusSeconds(60))

		assertEquals(identity.lastLoginAt, identity.recordLogin(createdAt).lastLoginAt)
	}

	@Test
	fun `rejects blank external subjects`() {
		assertFailsWith<IllegalArgumentException> {
			identity().copy(externalSubject = " ")
		}
	}

	private fun identity() = UserAuthIdentity(
		identityId = UUID.randomUUID(),
		userId = UserId(UUID.randomUUID()),
		provider = AuthProvider.FIREBASE,
		issuer = "https://securetoken.google.com/fitlake-dev",
		externalSubject = "firebase-uid",
		emailAtLinkTime = "user@example.com",
		createdAt = createdAt,
		lastLoginAt = createdAt,
	)
}
