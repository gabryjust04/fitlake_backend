package com.fitlake.user.infrastructure.persistence.repository

import com.fitlake.user.application.AuthIdentityConflictException
import com.fitlake.user.application.port.UserAuthIdentityRepository
import com.fitlake.user.domain.UserAuthIdentity
import com.fitlake.user.infrastructure.persistence.mapper.UserPersistenceMapper
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Repository

@Repository
class JpaUserAuthIdentityRepositoryAdapter(
	private val repository: SpringDataUserAuthIdentityRepository,
	private val mapper: UserPersistenceMapper,
) : UserAuthIdentityRepository {
	override fun findByIssuerAndExternalSubject(
		issuer: String,
		externalSubject: String,
	): UserAuthIdentity? = repository.findByIssuerAndExternalSubject(issuer, externalSubject)?.let(mapper::toDomain)

	override fun save(identity: UserAuthIdentity): UserAuthIdentity = try {
		mapper.toDomain(repository.saveAndFlush(mapper.toEntity(identity)))
	} catch (exception: DataIntegrityViolationException) {
		throw AuthIdentityConflictException(exception)
	}
}
