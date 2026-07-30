package com.fitlake.daily.application.capture

import com.fitlake.daily.application.DailyConflictException
import com.fitlake.daily.application.DailyNotFoundException
import com.fitlake.daily.application.DailyValidationException
import com.fitlake.daily.application.port.DailyCaptureRepository
import com.fitlake.daily.application.port.DailyDayRepository
import com.fitlake.daily.domain.capture.DailyCapture
import com.fitlake.daily.domain.capture.DailyCaptureId
import com.fitlake.daily.domain.capture.FoodUnitNormalizer
import com.fitlake.daily.domain.common.DailyDayStatus
import com.fitlake.shared.application.TransactionExecutor
import com.fitlake.user.domain.UserId
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.time.Clock

@Service
class DailyCaptureEditService(
	private val dayRepository: DailyDayRepository,
	private val captureRepository: DailyCaptureRepository,
	private val captureService: DailyCaptureService,
	private val payloadFactory: DailyPayloadFactory,
	private val transactionExecutor: TransactionExecutor,
	private val clock: Clock,
) {
	fun replace(userId: UserId, captureId: DailyCaptureId, input: DailyCaptureInput): DailyCapture {
		val payload = payloadFactory.create(input)
		return edit(userId, captureId) { it.replacePayload(payload, clock.instant()) }
	}

	fun updateFoodItem(
		userId: UserId,
		captureId: DailyCaptureId,
		itemTempId: String,
		quantity: BigDecimal,
		unit: String,
	): DailyCapture {
		val normalizedUnit = try {
			FoodUnitNormalizer.normalize(unit)
		} catch (exception: IllegalArgumentException) {
			throw DailyValidationException(exception.message ?: "Unsupported food unit")
		}
		return edit(userId, captureId) {
			try {
				it.updateFoodItem(itemTempId, quantity, normalizedUnit, clock.instant())
			} catch (exception: IllegalArgumentException) {
				throw DailyValidationException(exception.message ?: "Invalid food item update")
			}
		}
	}

	fun softDelete(userId: UserId, captureId: DailyCaptureId): DailyCapture =
		edit(userId, captureId) { it.softDelete(clock.instant()) }

	private fun edit(
		userId: UserId,
		captureId: DailyCaptureId,
		change: (DailyCapture) -> DailyCapture,
	): DailyCapture = transactionExecutor.required {
		val capture = captureService.requireOwned(userId, captureId)
		val day = dayRepository.findByIdForUpdate(capture.dayId)
			?: throw DailyNotFoundException("Capture day was not found")
		if (day.userId != userId) {
			throw DailyNotFoundException.capture(captureId.value)
		}
		if (day.status == DailyDayStatus.CONFIRMED) {
			throw DailyConflictException("Confirmed day captures cannot be edited")
		}
		try {
			captureRepository.save(change(capture))
		} catch (exception: IllegalStateException) {
			throw DailyConflictException(exception.message ?: "Capture cannot be edited")
		}
	}
}
