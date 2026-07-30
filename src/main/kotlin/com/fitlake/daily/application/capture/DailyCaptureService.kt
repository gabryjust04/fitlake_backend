package com.fitlake.daily.application.capture

import com.fitlake.daily.application.DailyConcurrentCreationException
import com.fitlake.daily.application.DailyConflictException
import com.fitlake.daily.application.DailyNotFoundException
import com.fitlake.daily.application.port.DailyCaptureRepository
import com.fitlake.daily.application.port.DailyDayRepository
import com.fitlake.daily.domain.capture.DailyCapture
import com.fitlake.daily.domain.capture.DailyCaptureId
import com.fitlake.daily.domain.common.DailyDay
import com.fitlake.shared.application.TransactionExecutor
import com.fitlake.user.domain.UserId
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.LocalDate

@Service
class DailyCaptureService(
	private val dayRepository: DailyDayRepository,
	private val captureRepository: DailyCaptureRepository,
	private val payloadFactory: DailyPayloadFactory,
	private val transactionExecutor: TransactionExecutor,
	private val clock: Clock,
) {
	fun create(userId: UserId, date: LocalDate, input: DailyCaptureInput): DailyCapture {
		val payload = payloadFactory.create(input)
		return try {
			transactionExecutor.required { createOnce(userId, date, payload) }
		} catch (exception: DailyConcurrentCreationException) {
			transactionExecutor.required { createOnce(userId, date, payload) }
		}
	}

	fun requireOwned(userId: UserId, captureId: DailyCaptureId): DailyCapture {
		val capture = captureRepository.findById(captureId)
			?: throw DailyNotFoundException.capture(captureId.value)
		if (capture.userId != userId) {
			throw DailyNotFoundException.capture(captureId.value)
		}
		return capture
	}

	private fun createOnce(
		userId: UserId,
		date: LocalDate,
		payload: com.fitlake.daily.domain.capture.DailyCapturePayload,
	): DailyCapture {
		val now = clock.instant()
		val day = dayRepository.findByUserIdAndDateForUpdate(userId, date)
			?: dayRepository.save(DailyDay.open(userId, date, now))
		if (day.status == com.fitlake.daily.domain.common.DailyDayStatus.CONFIRMED) {
			throw DailyConflictException("Confirmed day cannot receive new captures")
		}
		return captureRepository.save(DailyCapture.openFromUser(userId, day.dayId, payload, now))
	}
}
