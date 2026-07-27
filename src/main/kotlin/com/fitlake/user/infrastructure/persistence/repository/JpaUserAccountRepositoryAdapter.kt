package com.fitlake.user.infrastructure.persistence.repository

import com.fitlake.user.application.port.UserAccountRepository
import com.fitlake.user.domain.UserAccount
import com.fitlake.user.domain.UserId
import com.fitlake.user.infrastructure.persistence.mapper.UserPersistenceMapper
import org.springframework.stereotype.Repository

@Repository
class JpaUserAccountRepositoryAdapter(
	private val repository: SpringDataUserAccountRepository,
	private val mapper: UserPersistenceMapper,
) : UserAccountRepository {
	override fun findById(userId: UserId): UserAccount? =
		repository.findById(userId.value).orElse(null)?.let(mapper::toDomain)

	override fun save(userAccount: UserAccount): UserAccount =
		mapper.toDomain(repository.saveAndFlush(mapper.toEntity(userAccount)))
}
