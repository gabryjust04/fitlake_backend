package com.fitlake.daily.infrastructure.persistence

import org.junit.jupiter.api.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DailyCaptureAuditMigrationTest {
	private val initialAuditMigration: String by lazy {
		DailyCaptureAuditMigrationTest::class.java
			.getResourceAsStream("/db/migration/V5__add_daily_capture_content_audit.sql")
			.use { requireNotNull(it).bufferedReader().readText() }
	}
	private val lifecycleAuditMigration: String by lazy {
		DailyCaptureAuditMigrationTest::class.java
			.getResourceAsStream("/db/migration/V6__expand_daily_capture_lifecycle_audit.sql")
			.use { requireNotNull(it).bufferedReader().readText() }
	}

	@Test
	fun `migration adds owner preserving immutable capture audit`() {
		assertTrue(initialAuditMigration.contains("CREATE TABLE daily_capture_audit", ignoreCase = true))
		assertTrue(initialAuditMigration.contains("UNIQUE (capture_id, user_id)", ignoreCase = true))
		assertTrue(initialAuditMigration.contains("FOREIGN KEY (capture_id, user_id)", ignoreCase = true))
		assertTrue(initialAuditMigration.contains("CHECK (action IN ('UI_EDIT'))", ignoreCase = true))
		assertTrue(initialAuditMigration.contains("old_payload JSONB", ignoreCase = true))
		assertTrue(initialAuditMigration.contains("new_payload JSONB", ignoreCase = true))
		assertTrue(initialAuditMigration.contains("old_version BIGINT", ignoreCase = true))
		assertTrue(initialAuditMigration.contains("new_version BIGINT", ignoreCase = true))
	}

	@Test
	fun `migration widens calorie precision without introducing stale state`() {
		assertTrue(initialAuditMigration.contains("total_calories TYPE NUMERIC(18, 6)", ignoreCase = true))
		assertFalse(initialAuditMigration.contains("metrics_stale", ignoreCase = true))
		assertFalse(initialAuditMigration.contains("ALTER TABLE user_food", ignoreCase = true))
	}

	@Test
	fun `V6 replaces legacy AI outcomes and permits an absent input text`() {
		assertTrue(lifecycleAuditMigration.contains("ALTER COLUMN input_text DROP NOT NULL", ignoreCase = true))
		assertTrue(lifecycleAuditMigration.contains("WHERE status = 'NEEDS_CLARIFICATION'", ignoreCase = true))
		assertTrue(lifecycleAuditMigration.contains("SET status = 'INVALID_OUTPUT'", ignoreCase = true))
		assertTrue(lifecycleAuditMigration.contains("WHERE status = 'NO_OP'", ignoreCase = true))
		assertTrue(lifecycleAuditMigration.contains("SET status = 'NO_RELEVANT_DATA'", ignoreCase = true))

		val replacementConstraint = Regex(
			"ADD CONSTRAINT ck_ai_interpretation_log_status CHECK \\(.*?\\)",
			setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
		).find(lifecycleAuditMigration)?.value.orEmpty()
		assertTrue(replacementConstraint.contains("'SUCCESS'"))
		assertTrue(replacementConstraint.contains("'FAILED'"))
		assertTrue(replacementConstraint.contains("'INVALID_OUTPUT'"))
		assertTrue(replacementConstraint.contains("'NO_RELEVANT_DATA'"))
		assertFalse(replacementConstraint.contains("'NO_OP'"))
		assertFalse(replacementConstraint.contains("'NEEDS_CLARIFICATION'"))
	}

	@Test
	fun `V6 expands audit to lifecycle actions with optional state and owner safe linkage`() {
		assertFalse(lifecycleAuditMigration.contains("CREATE TABLE", ignoreCase = true))
		assertTrue(lifecycleAuditMigration.contains("ALTER COLUMN old_payload DROP NOT NULL", ignoreCase = true))
		assertTrue(lifecycleAuditMigration.contains("ALTER COLUMN new_payload DROP NOT NULL", ignoreCase = true))
		assertTrue(lifecycleAuditMigration.contains("ALTER COLUMN old_version DROP NOT NULL", ignoreCase = true))
		assertTrue(lifecycleAuditMigration.contains("ALTER COLUMN new_version DROP NOT NULL", ignoreCase = true))
		assertTrue(lifecycleAuditMigration.contains("ADD COLUMN old_status VARCHAR(20)", ignoreCase = true))
		assertTrue(lifecycleAuditMigration.contains("ADD COLUMN new_status VARCHAR(20)", ignoreCase = true))
		assertTrue(lifecycleAuditMigration.contains("ADD COLUMN actor VARCHAR(20)", ignoreCase = true))
		assertTrue(lifecycleAuditMigration.contains("ADD COLUMN reason_code VARCHAR(100)", ignoreCase = true))
		assertTrue(lifecycleAuditMigration.contains("ADD COLUMN related_capture_id UUID", ignoreCase = true))
		assertTrue(lifecycleAuditMigration.contains("FOREIGN KEY (related_capture_id, user_id)", ignoreCase = true))
		assertTrue(lifecycleAuditMigration.contains("new_version = 0", ignoreCase = true))
		listOf(
			"'CREATE'",
			"'ACCEPT'",
			"'REJECT'",
			"'UI_EDIT'",
			"'SOFT_DELETE'",
			"'REPLACED_BY_REPROCESS'",
		).forEach { action -> assertTrue(lifecycleAuditMigration.contains(action)) }
	}
}
