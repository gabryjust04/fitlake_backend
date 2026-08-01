package com.fitlake.daily.infrastructure.persistence

import org.junit.jupiter.api.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DailyCaptureAuditMigrationTest {
	private val migration: String by lazy {
		DailyCaptureAuditMigrationTest::class.java
			.getResourceAsStream("/db/migration/V5__add_daily_capture_content_audit.sql")
			.use { requireNotNull(it).bufferedReader().readText() }
	}

	@Test
	fun `migration adds owner preserving immutable capture audit`() {
		assertTrue(migration.contains("CREATE TABLE daily_capture_audit", ignoreCase = true))
		assertTrue(migration.contains("UNIQUE (capture_id, user_id)", ignoreCase = true))
		assertTrue(migration.contains("FOREIGN KEY (capture_id, user_id)", ignoreCase = true))
		assertTrue(migration.contains("CHECK (action IN ('UI_EDIT'))", ignoreCase = true))
		assertTrue(migration.contains("old_payload JSONB", ignoreCase = true))
		assertTrue(migration.contains("new_payload JSONB", ignoreCase = true))
		assertTrue(migration.contains("old_version BIGINT", ignoreCase = true))
		assertTrue(migration.contains("new_version BIGINT", ignoreCase = true))
	}

	@Test
	fun `migration widens calorie precision without introducing stale state`() {
		assertTrue(migration.contains("total_calories TYPE NUMERIC(18, 6)", ignoreCase = true))
		assertFalse(migration.contains("metrics_stale", ignoreCase = true))
		assertFalse(migration.contains("ALTER TABLE user_food", ignoreCase = true))
	}
}
