package com.fitlake.daily.infrastructure.persistence.repository

import com.fitlake.daily.domain.capture.DailyCaptureStatus
import com.fitlake.daily.infrastructure.persistence.entity.DailyCaptureEntity
import com.fitlake.daily.infrastructure.persistence.entity.DailyDayEntity
import com.fitlake.daily.infrastructure.persistence.entity.DailyMetricsEntity
import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.LocalDate
import java.util.UUID

interface SpringDataDailyDayRepository : JpaRepository<DailyDayEntity, UUID> {
	fun findByUserIdAndDayDate(userId: UUID, dayDate: LocalDate): DailyDayEntity?

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select day from DailyDayEntity day where day.dayId = :dayId")
	fun findByDayIdForUpdate(@Param("dayId") dayId: UUID): DailyDayEntity?

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select day from DailyDayEntity day where day.userId = :userId and day.dayDate = :dayDate")
	fun findByUserIdAndDayDateForUpdate(
		@Param("userId") userId: UUID,
		@Param("dayDate") dayDate: LocalDate,
	): DailyDayEntity?
}

interface SpringDataDailyCaptureRepository : JpaRepository<DailyCaptureEntity, UUID> {
	fun findByCaptureIdAndUserId(captureId: UUID, userId: UUID): DailyCaptureEntity?

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select capture from DailyCaptureEntity capture where capture.captureId = :captureId")
	fun findByCaptureIdForUpdate(@Param("captureId") captureId: UUID): DailyCaptureEntity?

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query(
		"select capture from DailyCaptureEntity capture " +
			"where capture.captureId = :captureId and capture.userId = :userId",
	)
	fun findByCaptureIdAndUserIdForUpdate(
		@Param("captureId") captureId: UUID,
		@Param("userId") userId: UUID,
	): DailyCaptureEntity?

	fun findBySourceEventId(sourceEventId: UUID): DailyCaptureEntity?

	fun findAllByUserIdAndDayIdOrderByCreatedAtAscCaptureIdAsc(
		userId: UUID,
		dayId: UUID,
	): List<DailyCaptureEntity>

	fun findAllByUserIdAndDayIdAndStatusOrderByCreatedAtAscCaptureIdAsc(
		userId: UUID,
		dayId: UUID,
		status: DailyCaptureStatus,
	): List<DailyCaptureEntity>

	fun existsByUserIdAndDayIdAndStatus(
		userId: UUID,
		dayId: UUID,
		status: DailyCaptureStatus,
	): Boolean
}

interface SpringDataDailyMetricsRepository : JpaRepository<DailyMetricsEntity, UUID> {
	fun findByUserIdAndDayDate(userId: UUID, dayDate: LocalDate): DailyMetricsEntity?
}
