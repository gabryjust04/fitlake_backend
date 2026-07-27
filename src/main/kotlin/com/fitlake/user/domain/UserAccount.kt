package com.fitlake.user.domain

import java.time.Instant
import java.time.ZoneId

data class UserAccount(
	val userId: UserId,
	val email: String?,
	val displayName: String?,
	val timezone: ZoneId,
	val createdAt: Instant,
	val updatedAt: Instant,
) {
	init {
		require(email == null || email.isNotBlank()) { "Email must be null or non-blank" }
		require(email == null || email.length <= 320) { "Email must not exceed 320 characters" }
		require(displayName == null || displayName.isNotBlank()) {
			"Display name must be null or non-blank"
		}
		require(displayName == null || displayName.length <= 255) {
			"Display name must not exceed 255 characters"
		}
		require(!updatedAt.isBefore(createdAt)) { "Updated timestamp cannot precede creation" }
	}
}
