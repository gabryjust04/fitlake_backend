package com.fitlake.daily.infrastructure.persistence

import org.junit.jupiter.api.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DailyTenantOwnershipMigrationTest {
	private val migration: String by lazy {
		DailyTenantOwnershipMigrationTest::class.java
			.getResourceAsStream("/db/migration/V7__enforce_daily_tenant_references.sql")
			.use { requireNotNull(it).bufferedReader().readText() }
	}

	@Test
	fun `V7 adds every owner preserving Daily relationship without replacing prior migrations`() {
		assertFalse(migration.contains("CREATE TABLE", ignoreCase = true))
		assertTrue(migration.contains("UNIQUE (day_id, user_id)", ignoreCase = true))
		assertTrue(migration.contains("UNIQUE (day_id, user_id, day_date)", ignoreCase = true))
		assertTrue(migration.contains("UNIQUE (inbox_event_id, user_id)", ignoreCase = true))

		listOf(
			"FOREIGN KEY (day_id, user_id)\n            REFERENCES daily_day (day_id, user_id)",
			"FOREIGN KEY (source_event_id, user_id)\n            REFERENCES daily_inbox_event (inbox_event_id, user_id)",
			"FOREIGN KEY (replaces_capture_id, user_id)\n            REFERENCES daily_capture (capture_id, user_id)",
			"FOREIGN KEY (inbox_event_id, user_id)\n            REFERENCES daily_inbox_event (inbox_event_id, user_id)",
			"FOREIGN KEY (capture_id, user_id)\n            REFERENCES daily_capture (capture_id, user_id)",
			"FOREIGN KEY (day_id, user_id, day_date)\n            REFERENCES daily_day (day_id, user_id, day_date)",
		).forEach { relationship -> assertTrue(migration.contains(relationship, ignoreCase = true)) }

		assertTrue(Regex("NOT VALID", RegexOption.IGNORE_CASE).findAll(migration).count() == 7)
		assertTrue(Regex("VALIDATE CONSTRAINT", RegexOption.IGNORE_CASE).findAll(migration).count() == 7)
	}
}
