package com.fitlake.daily.application

import com.fitlake.daily.application.port.DailyCaptureRepository
import com.fitlake.daily.application.port.DailyDayRepository
import com.fitlake.daily.application.port.DailyMetricsRepository
import com.fitlake.daily.domain.capture.DailyCapture
import com.fitlake.daily.domain.common.DailyDay
import com.fitlake.daily.domain.common.DailyDayId
import com.fitlake.daily.domain.metrics.DailyMetrics
import com.fitlake.shared.application.TransactionExecutor
import com.fitlake.support.InMemoryDailyCaptureRepository
import com.fitlake.support.InMemoryDailyDayRepository
import com.fitlake.support.InMemoryDailyMetricsRepository
import com.fitlake.user.domain.UserId
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import kotlin.test.assertEquals

class DailyQueryServiceTest {
	@Test
	fun `getDay holds the day lock while assembling one aggregate snapshot`() {
		val transaction = RecordingTransactionExecutor()
		val storedDays = InMemoryDailyDayRepository()
		val storedCaptures = InMemoryDailyCaptureRepository()
		val storedMetrics = InMemoryDailyMetricsRepository()
		val userId = UserId(UUID.randomUUID())
		val date = LocalDate.parse("2026-08-01")
		val day = storedDays.save(DailyDay.open(userId, date, Instant.parse("2026-08-01T08:00:00Z")))
		val days = SnapshotDayRepository(storedDays, transaction)
		val captures = SnapshotCaptureRepository(storedCaptures, transaction)
		val metrics = SnapshotMetricsRepository(storedMetrics, transaction)
		val service = DailyQueryService(days, captures, metrics, transaction)

		val view = service.getDay(userId, date)

		assertEquals(day, view.day)
		assertEquals(emptyList(), view.captures)
		assertEquals(null, view.metrics)
		assertEquals(1, transaction.requiredCalls)
		assertEquals(1, days.lockedReads)
		assertEquals(0, days.unlockedReads)
		assertEquals(
			listOf("transaction:start", "day:lock", "captures:read", "metrics:read", "transaction:end"),
			transaction.events,
		)
	}

	private class RecordingTransactionExecutor : TransactionExecutor {
		val events = mutableListOf<String>()
		var requiredCalls = 0
			private set
		var active = false
			private set

		override fun <T : Any> required(action: () -> T): T {
			check(!active) { "Test transaction cannot be nested" }
			requiredCalls += 1
			active = true
			events += "transaction:start"
			return try {
				action()
			} finally {
				events += "transaction:end"
				active = false
			}
		}
	}

	private class SnapshotDayRepository(
		private val delegate: DailyDayRepository,
		private val transaction: RecordingTransactionExecutor,
	) : DailyDayRepository by delegate {
		var lockedReads = 0
			private set
		var unlockedReads = 0
			private set

		override fun findByUserIdAndDate(userId: UserId, date: LocalDate): DailyDay? {
			unlockedReads += 1
			return delegate.findByUserIdAndDate(userId, date)
		}

		override fun findByUserIdAndDateForUpdate(userId: UserId, date: LocalDate): DailyDay? {
			check(transaction.active) { "Day lock must be acquired inside the snapshot transaction" }
			lockedReads += 1
			transaction.events += "day:lock"
			return delegate.findByUserIdAndDateForUpdate(userId, date)
		}
	}

	private class SnapshotCaptureRepository(
		private val delegate: DailyCaptureRepository,
		private val transaction: RecordingTransactionExecutor,
	) : DailyCaptureRepository by delegate {
		override fun findAllByUserIdAndDayId(userId: UserId, dayId: DailyDayId): List<DailyCapture> {
			check(transaction.active) { "Captures must be read inside the snapshot transaction" }
			transaction.events += "captures:read"
			return delegate.findAllByUserIdAndDayId(userId, dayId)
		}
	}

	private class SnapshotMetricsRepository(
		private val delegate: DailyMetricsRepository,
		private val transaction: RecordingTransactionExecutor,
	) : DailyMetricsRepository by delegate {
		override fun findByDayId(dayId: DailyDayId): DailyMetrics? {
			check(transaction.active) { "Metrics must be read inside the snapshot transaction" }
			transaction.events += "metrics:read"
			return delegate.findByDayId(dayId)
		}
	}
}
