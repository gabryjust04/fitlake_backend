package com.fitlake.daily.application

import com.fitlake.daily.application.port.DailyCaptureRepository
import com.fitlake.daily.application.port.DailyDayRepository
import com.fitlake.daily.application.port.DailyMetricsRepository
import com.fitlake.daily.application.finalization.DailyMetricsProjectionService
import com.fitlake.daily.domain.capture.DailyCapture
import com.fitlake.daily.domain.capture.DailyCapturePayload
import com.fitlake.daily.domain.capture.DailyFoodQuantityUnit
import com.fitlake.daily.domain.common.DailyDay
import com.fitlake.daily.domain.common.DailyDayId
import com.fitlake.daily.domain.common.DailyDayStatus
import com.fitlake.daily.domain.metrics.DailyMetrics
import com.fitlake.shared.application.TransactionExecutor
import com.fitlake.support.InMemoryDailyCaptureRepository
import com.fitlake.support.InMemoryDailyDayRepository
import com.fitlake.support.InMemoryDailyMetricsRepository
import com.fitlake.support.dailyFieldsPayload
import com.fitlake.support.dailyFoodPayload
import com.fitlake.support.manualNutritionItem
import com.fitlake.user.domain.UserId
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate
import java.math.BigDecimal
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
		val service = DailyQueryService(days, captures, metrics, DailyMetricsProjectionService(), transaction)

		val view = service.getDay(userId, date)

		assertEquals(day, view.day)
		assertEquals(emptyList(), view.captures)
		assertEquals(DailyDayStatus.OPEN, view.metrics?.status)
		assertEquals(null, view.metrics?.totalCalories)
		assertEquals(emptyList(), view.metrics?.foodLog)
		assertEquals(emptyList(), view.metrics?.generatedFromCaptureIds)
		assertEquals(null, view.metrics?.confirmedAt)
		assertEquals(0, storedMetrics.saveCount)
		assertEquals(1, transaction.requiredCalls)
		assertEquals(1, days.lockedReads)
		assertEquals(0, days.unlockedReads)
		assertEquals(
			listOf("transaction:start", "day:lock", "captures:read", "transaction:end"),
			transaction.events,
		)
	}

	@Test
	fun `open day metrics reuse finalization projection and never persist`() {
		val transaction = RecordingTransactionExecutor()
		val days = InMemoryDailyDayRepository()
		val captures = InMemoryDailyCaptureRepository()
		val metrics = InMemoryDailyMetricsRepository()
		val userId = UserId(UUID.randomUUID())
		val date = LocalDate.parse("2026-08-02")
		val openedAt = Instant.parse("2026-08-02T08:00:00Z")
		val day = days.save(DailyDay.open(userId, date, openedAt))
		val mixedPayload = DailyCapturePayload.fromEntries(
			dailyFoodPayload(
				listOf(
					manualNutritionItem(
						foodName = "accepted stored snapshot",
						quantity = BigDecimal("100"),
						unit = DailyFoodQuantityUnit.GRAM,
						calories = BigDecimal("250"),
						protein = BigDecimal("20"),
						carbohydrates = BigDecimal("30"),
						fat = BigDecimal("8"),
					),
			),
		).entries + dailyFieldsPayload(bodyWeightKg = BigDecimal("78.2")).entries,
		)
		val acceptedMixed = captures.save(
			DailyCapture.openFromUser(userId, day.dayId, mixedPayload, openedAt.plusSeconds(10))
				.accept(openedAt.plusSeconds(20)),
		)
		captures.save(
			DailyCapture.openFromUser(
				userId,
				day.dayId,
				dailyFieldsPayload(sleepHours = BigDecimal("3")),
				openedAt.plusSeconds(30),
			),
		)
		captures.save(
			DailyCapture.openFromUser(
				userId,
				day.dayId,
				dailyFieldsPayload(bodyWeightKg = BigDecimal("499")),
				openedAt.plusSeconds(40),
			).reject(openedAt.plusSeconds(50)),
		)
		captures.save(
			DailyCapture.openFromUser(
				userId,
				day.dayId,
				dailyFoodPayload(
					listOf(
						manualNutritionItem(
							foodName = "deleted stored snapshot",
							quantity = BigDecimal.ONE,
							unit = DailyFoodQuantityUnit.SERVING,
							calories = BigDecimal("900"),
						),
					),
				),
				openedAt.plusSeconds(60),
			).accept(openedAt.plusSeconds(70)).softDelete(openedAt.plusSeconds(80)),
		)
		val service = DailyQueryService(
			days,
			captures,
			metrics,
			DailyMetricsProjectionService(),
			transaction,
		)

		val current = service.getMetrics(userId, date)
		val dayView = service.getDay(userId, date)

		assertEquals(DailyDayStatus.OPEN, current.status)
		assertEquals(BigDecimal("78.2"), current.bodyWeightKg)
		assertEquals(null, current.sleepHours)
		assertEquals(BigDecimal("250"), current.totalCalories)
		assertEquals(BigDecimal("20"), current.proteinG)
		assertEquals(listOf(acceptedMixed.captureId.value), current.generatedFromCaptureIds)
		assertEquals(current, dayView.metrics)
		assertEquals(DailyDayStatus.OPEN, days.findById(day.dayId)?.status)
		assertEquals(0, metrics.saveCount)
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
