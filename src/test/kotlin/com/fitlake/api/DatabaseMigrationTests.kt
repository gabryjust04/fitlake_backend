package com.fitlake.api

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class DatabaseMigrationTests {

	@Test
	fun `initial migration creates exactly the MVP tables`() {
		val migration = DatabaseMigrationTests::class.java
			.getResourceAsStream("/db/migration/V1__initialize_fitlake_daily_schema.sql")
			.use { requireNotNull(it).bufferedReader().readText() }

		val tables = Regex("(?im)^CREATE TABLE\\s+([a-z_][a-z0-9_]*)\\b")
			.findAll(migration)
			.map { it.groupValues[1] }
			.toSet()

		assertEquals(
			setOf(
				"user_account",
				"user_channel_identity",
				"daily_day",
				"daily_inbox_event",
				"daily_capture",
				"ai_interpretation_log",
				"daily_metrics",
			),
			tables,
		)
		assertFalse(migration.contains("event_publication", ignoreCase = true))
	}

	@Test
	fun `authentication migration adds Firebase identity without making email an identifier`() {
		val migration = DatabaseMigrationTests::class.java
			.getResourceAsStream("/db/migration/V2__add_firebase_auth_identity.sql")
			.use { requireNotNull(it).bufferedReader().readText() }

		assertEquals(
			setOf("user_auth_identity"),
			Regex("(?im)^CREATE TABLE\\s+([a-z_][a-z0-9_]*)\\b")
				.findAll(migration)
				.map { it.groupValues[1] }
				.toSet(),
		)
		assertFalse(migration.contains("CREATE TABLE daily_", ignoreCase = true))
		kotlin.test.assertTrue(migration.contains("UNIQUE (issuer, external_subject)"))
		kotlin.test.assertTrue(migration.contains("UNIQUE (user_id, issuer)"))
		kotlin.test.assertTrue(migration.contains("DROP CONSTRAINT uq_user_account_email"))
	}

}
