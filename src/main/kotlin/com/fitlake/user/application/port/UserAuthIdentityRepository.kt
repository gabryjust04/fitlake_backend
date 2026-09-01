package com.fitlake.user.application.port

import com.fitlake.user.domain.UserAuthIdentity

interface UserAuthIdentityRepository {
	fun findByIssuerAndExternalSubject(issuer: String, externalSubject: String): UserAuthIdentity?

	fun save(identity: UserAuthIdentity): UserAuthIdentity
}
