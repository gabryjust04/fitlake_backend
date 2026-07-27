package com.fitlake.user.domain

import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class UserAccountTest {
	private val now = Instant.parse("2026-01-01T10:00:00Z")

	@Test
	fun `preserves internal ID and profile values`() {
		val userId = UserId(UUID.randomUUID())
		val account = UserAccount(
			userId = userId,
			email = "user@example.com",
			displayName = "Andrea",
			timezone = ZoneId.of("Europe/Rome"),
			createdAt = now,
			updatedAt = now,
		)

		assertEquals(userId, account.userId)
		assertEquals("Europe/Rome", account.timezone.id)
	}

	@Test
	fun `rejects a blank email`() {
		assertFailsWith<IllegalArgumentException> {
			UserAccount(
				userId = UserId(UUID.randomUUID()),
				email = " ",
				displayName = null,
				timezone = ZoneId.of("Europe/Rome"),
				createdAt = now,
				updatedAt = now,
			)
		}
	}
}
