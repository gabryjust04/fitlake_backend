package com.fitlake.daily.infrastructure.persistence.repository

import com.fitlake.daily.application.DailyConcurrentCreationException
import com.fitlake.daily.application.port.DailyCaptureRepository
import com.fitlake.daily.application.port.DailyDayRepository
import com.fitlake.daily.application.port.DailyMetricsRepository
import com.fitlake.daily.domain.capture.DailyCapture
import com.fitlake.daily.domain.capture.DailyCaptureId
import com.fitlake.daily.domain.capture.DailyCaptureStatus
import com.fitlake.daily.domain.common.DailyDay
import com.fitlake.daily.domain.common.DailyDayId
import com.fitlake.daily.domain.metrics.DailyMetrics
import com.fitlake.daily.infrastructure.persistence.mapper.DailyPersistenceMapper
import com.fitlake.user.domain.UserId
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Repository
import java.time.LocalDate

@Repository
class JpaDailyDayRepository(
	private val repository: SpringDataDailyDayRepository,
	private val mapper: DailyPersistenceMapper,
) : DailyDayRepository {
	override fun findById(dayId: DailyDayId): DailyDay? =
		repository.findById(dayId.value).orElse(null)?.let(mapper::toDomain)

	override fun findByIdForUpdate(dayId: DailyDayId): DailyDay? =
		repository.findByDayIdForUpdate(dayId.value)?.let(mapper::toDomain)

	override fun findByUserIdAndDate(userId: UserId, date: LocalDate): DailyDay? =
		repository.findByUserIdAndDayDate(userId.value, date)?.let(mapper::toDomain)

	override fun findByUserIdAndDateForUpdate(userId: UserId, date: LocalDate): DailyDay? =
		repository.findByUserIdAndDayDateForUpdate(userId.value, date)?.let(mapper::toDomain)

	override fun save(day: DailyDay): DailyDay = try {
		mapper.toDomain(repository.saveAndFlush(mapper.toEntity(day)))
	} catch (exception: DataIntegrityViolationException) {
		throw DailyConcurrentCreationException(exception)
	}
}

@Repository
class JpaDailyCaptureRepository(
	private val repository: SpringDataDailyCaptureRepository,
	private val mapper: DailyPersistenceMapper,
) : DailyCaptureRepository {
	override fun findById(captureId: DailyCaptureId): DailyCapture? =
		repository.findById(captureId.value).orElse(null)?.let(mapper::toDomain)

	override fun findAllByUserIdAndDayId(userId: UserId, dayId: DailyDayId): List<DailyCapture> =
		repository.findAllByUserIdAndDayIdOrderByCreatedAtAscCaptureIdAsc(userId.value, dayId.value)
			.map(mapper::toDomain)

	override fun findAllByUserIdAndDayIdAndStatus(
		userId: UserId,
		dayId: DailyDayId,
		status: DailyCaptureStatus,
	): List<DailyCapture> =
		repository.findAllByUserIdAndDayIdAndStatusOrderByCreatedAtAscCaptureIdAsc(
			userId.value,
			dayId.value,
			status,
		).map(mapper::toDomain)

	override fun existsByUserIdAndDayIdAndStatus(
		userId: UserId,
		dayId: DailyDayId,
		status: DailyCaptureStatus,
	): Boolean = repository.existsByUserIdAndDayIdAndStatus(userId.value, dayId.value, status)

	override fun save(capture: DailyCapture): DailyCapture =
		mapper.toDomain(repository.saveAndFlush(mapper.toEntity(capture)))
}

@Repository
class JpaDailyMetricsRepository(
	private val repository: SpringDataDailyMetricsRepository,
	private val mapper: DailyPersistenceMapper,
) : DailyMetricsRepository {
	override fun findByDayId(dayId: DailyDayId): DailyMetrics? =
		repository.findById(dayId.value).orElse(null)?.let(mapper::toDomain)

	override fun findByUserIdAndDate(userId: UserId, date: LocalDate): DailyMetrics? =
		repository.findByUserIdAndDayDate(userId.value, date)?.let(mapper::toDomain)

	override fun save(metrics: DailyMetrics): DailyMetrics =
		mapper.toDomain(repository.saveAndFlush(mapper.toEntity(metrics)))
}
