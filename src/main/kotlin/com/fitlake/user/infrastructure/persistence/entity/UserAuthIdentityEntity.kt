package com.fitlake.user.infrastructure.persistence.entity

import com.fitlake.user.domain.AuthProvider
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "user_auth_identity")
class UserAuthIdentityEntity(
	@Id
	@Column(name = "auth_identity_id", nullable = false, updatable = false)
	var identityId: UUID,

	@Column(name = "user_id", nullable = false, updatable = false)
	var userId: UUID,

	@Enumerated(EnumType.STRING)
	@Column(name = "provider", nullable = false, length = 40, updatable = false)
	var provider: AuthProvider,

	@Column(name = "issuer", nullable = false, length = 255, updatable = false)
	var issuer: String,

	@Column(name = "external_subject", nullable = false, length = 255, updatable = false)
	var externalSubject: String,

	@Column(name = "email_at_link_time", length = 320, updatable = false)
	var emailAtLinkTime: String?,

	@Column(name = "created_at", nullable = false, updatable = false)
	var createdAt: Instant,

	@Column(name = "last_login_at", nullable = false)
	var lastLoginAt: Instant,
)
