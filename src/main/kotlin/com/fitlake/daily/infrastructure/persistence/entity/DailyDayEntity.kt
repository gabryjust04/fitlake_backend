package com.fitlake.daily.infrastructure.persistence.entity

import com.fitlake.daily.domain.common.DailyDayStatus
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.Version
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

@Entity
@Table(name = "daily_day")
class DailyDayEntity(
	@Id
	@Column(name = "day_id", nullable = false, updatable = false)
	var dayId: UUID,

	@Column(name = "user_id", nullable = false, updatable = false)
	var userId: UUID,

	@Column(name = "day_date", nullable = false, updatable = false)
	var dayDate: LocalDate,

	@Enumerated(EnumType.STRING)
	@Column(name = "status", nullable = false, length = 20)
	var status: DailyDayStatus,

	@Column(name = "opened_at", nullable = false, updatable = false)
	var openedAt: Instant,

	@Column(name = "confirmed_at")
	var confirmedAt: Instant?,

	@Column(name = "reopened_at")
	var reopenedAt: Instant?,

	@Column(name = "created_at", nullable = false, updatable = false)
	var createdAt: Instant,

	@Column(name = "updated_at", nullable = false)
	var updatedAt: Instant,

	@Version
	@Column(name = "version", nullable = false)
	var version: Long,
)
