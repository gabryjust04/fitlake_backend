package com.fitlake.api

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

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
		assertTrue(migration.contains("UNIQUE (issuer, external_subject)"))
		assertTrue(migration.contains("UNIQUE (user_id, issuer)"))
		assertTrue(migration.contains("DROP CONSTRAINT uq_user_account_email"))
	}

	@Test
	fun `Daily AI migration adds audit linkage and database idempotency without new tables`() {
		val migration = DatabaseMigrationTests::class.java
			.getResourceAsStream("/db/migration/V3__add_daily_ai_message_audit.sql")
			.use { requireNotNull(it).bufferedReader().readText() }

		assertFalse(migration.contains("CREATE TABLE", ignoreCase = true))
		assertTrue(migration.contains("ADD COLUMN replaces_capture_id UUID", ignoreCase = true))
		assertTrue(migration.contains("ADD COLUMN processing_started_at TIMESTAMPTZ", ignoreCase = true))
		assertTrue(migration.contains("ADD COLUMN processing_attempt_id UUID", ignoreCase = true))
		assertTrue(migration.contains("ALTER COLUMN processing_started_at SET NOT NULL", ignoreCase = true))
		assertTrue(migration.contains("ALTER COLUMN processing_attempt_id SET NOT NULL", ignoreCase = true))
		assertTrue(migration.contains("(user_id, channel, source_message_id)", ignoreCase = true))
		assertTrue(migration.contains("uq_daily_capture_source_event", ignoreCase = true))
		assertTrue(migration.contains("uq_ai_interpretation_log_inbox_event", ignoreCase = true))
		assertTrue(migration.contains("'NO_OP'"))
	}

}
