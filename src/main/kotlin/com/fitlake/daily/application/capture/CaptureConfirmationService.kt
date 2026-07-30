package com.fitlake.daily.application.capture

import com.fitlake.daily.application.DailyConflictException
import com.fitlake.daily.application.DailyNotFoundException
import com.fitlake.daily.application.port.DailyCaptureRepository
import com.fitlake.daily.application.port.DailyDayRepository
import com.fitlake.daily.domain.capture.DailyCapture
import com.fitlake.daily.domain.capture.DailyCaptureId
import com.fitlake.daily.domain.common.DailyDayStatus
import com.fitlake.shared.application.TransactionExecutor
import com.fitlake.user.domain.UserId
import org.springframework.stereotype.Service
import java.time.Clock

@Service
class CaptureConfirmationService(
	private val dayRepository: DailyDayRepository,
	private val captureRepository: DailyCaptureRepository,
	private val captureService: DailyCaptureService,
	private val transactionExecutor: TransactionExecutor,
	private val clock: Clock,
) {
	fun accept(userId: UserId, captureId: DailyCaptureId): DailyCapture =
		transition(userId, captureId) { it.accept(clock.instant()) }

	fun reject(userId: UserId, captureId: DailyCaptureId): DailyCapture =
		transition(userId, captureId) { it.reject(clock.instant()) }

	private fun transition(
		userId: UserId,
		captureId: DailyCaptureId,
		transition: (DailyCapture) -> DailyCapture,
	): DailyCapture = transactionExecutor.required {
		val capture = captureService.requireOwned(userId, captureId)
		val day = dayRepository.findByIdForUpdate(capture.dayId)
			?: throw DailyNotFoundException("Capture day was not found")
		if (day.userId != userId) {
			throw DailyNotFoundException.capture(captureId.value)
		}
		if (day.status == DailyDayStatus.CONFIRMED) {
			throw DailyConflictException("Confirmed day captures cannot change status")
		}
		try {
			captureRepository.save(transition(capture))
		} catch (exception: IllegalStateException) {
			throw DailyConflictException(exception.message ?: "Invalid capture transition")
		}
	}
}
