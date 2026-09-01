package com.fitlake.user.infrastructure.persistence.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "user_account")
class UserAccountEntity(
	@Id
	@Column(name = "user_id", nullable = false, updatable = false)
	var userId: UUID,

	@Column(name = "email", length = 320)
	var email: String?,

	@Column(name = "display_name", length = 255)
	var displayName: String?,

	@Column(name = "timezone", nullable = false, length = 63)
	var timezone: String,

	@Column(name = "created_at", nullable = false, updatable = false)
	var createdAt: Instant,

	@Column(name = "updated_at", nullable = false)
	var updatedAt: Instant,
)
