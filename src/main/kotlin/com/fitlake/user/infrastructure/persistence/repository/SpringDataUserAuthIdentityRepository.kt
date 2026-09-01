package com.fitlake.user.infrastructure.persistence.repository

import com.fitlake.user.infrastructure.persistence.entity.UserAuthIdentityEntity
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface SpringDataUserAuthIdentityRepository : JpaRepository<UserAuthIdentityEntity, UUID> {
	fun findByIssuerAndExternalSubject(issuer: String, externalSubject: String): UserAuthIdentityEntity?
}
