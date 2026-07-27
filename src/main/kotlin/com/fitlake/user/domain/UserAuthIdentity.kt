package com.fitlake.user.domain

import java.time.Instant
import java.util.UUID

data class UserAuthIdentity(
	val identityId: UUID,
	val userId: UserId,
	val provider: AuthProvider,
	val issuer: String,
	val externalSubject: String,
	val emailAtLinkTime: String?,
	val createdAt: Instant,
	val lastLoginAt: Instant,
) {
	init {
		require(issuer.isNotBlank()) { "Issuer must not be blank" }
		require(issuer.length <= 255) { "Issuer must not exceed 255 characters" }
		require(externalSubject.isNotBlank()) { "External subject must not be blank" }
		require(externalSubject.length <= 255) { "External subject must not exceed 255 characters" }
		require(emailAtLinkTime == null || emailAtLinkTime.isNotBlank()) {
			"Linked email must be null or non-blank"
		}
		require(emailAtLinkTime == null || emailAtLinkTime.length <= 320) {
			"Linked email must not exceed 320 characters"
		}
		require(!lastLoginAt.isBefore(createdAt)) { "Last login cannot precede creation" }
	}

	fun recordLogin(at: Instant): UserAuthIdentity =
		copy(lastLoginAt = maxOf(lastLoginAt, at))
}
