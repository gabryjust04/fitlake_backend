package com.fitlake.daily.domain

import com.fitlake.daily.domain.capture.DAILY_CAPTURE_SCHEMA_VERSION
import com.fitlake.daily.domain.capture.DailyCaptureEntry
import com.fitlake.daily.domain.capture.DailyCaptureEntryType
import com.fitlake.daily.domain.capture.DailyCapturePayload
import com.fitlake.daily.domain.capture.DailyCaptureType
import com.fitlake.daily.domain.capture.DailyScalarUnit
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class DailyCapturePayloadTest {
	@Test
	fun `typed entries create the current v2 payload and derived projection`() {
		val payload = DailyCapturePayload.fromEntries(
			listOf(
				DailyCaptureEntry(
					entryId = UUID.randomUUID(),
					type = DailyCaptureEntryType.WEIGHT,
					value = BigDecimal("78.4"),
					unit = DailyScalarUnit.KILOGRAM,
				),
			),
		)

		assertEquals(DAILY_CAPTURE_SCHEMA_VERSION, payload.schemaVersion)
		assertEquals(DailyCaptureType.DAILY_FIELDS, payload.type)
		assertEquals(BigDecimal("78.4"), payload.fields.bodyWeightKg)
	}

	@Test
	fun `schema v1 cannot be instantiated`() {
		val entry = DailyCaptureEntry(
			entryId = UUID.randomUUID(),
			type = DailyCaptureEntryType.NOTE,
			text = "nota",
		)

		assertFailsWith<IllegalArgumentException> {
			DailyCapturePayload(
				type = DailyCaptureType.NOTE,
				note = "nota",
				schemaVersion = 1,
				entries = listOf(entry),
			)
		}
	}

	@Test
	fun `empty typed payload is rejected`() {
		assertFailsWith<IllegalArgumentException> { DailyCapturePayload.fromEntries(emptyList()) }
	}
}
