package com.fitlake.user.infrastructure.persistence.mapper

import com.fitlake.user.domain.UserAccount
import com.fitlake.user.domain.UserAuthIdentity
import com.fitlake.user.domain.UserId
import com.fitlake.user.infrastructure.persistence.entity.UserAccountEntity
import com.fitlake.user.infrastructure.persistence.entity.UserAuthIdentityEntity
import org.springframework.stereotype.Component
import java.time.ZoneId

@Component
class UserPersistenceMapper {
	fun toDomain(entity: UserAccountEntity): UserAccount = UserAccount(
		userId = UserId(entity.userId),
		email = entity.email,
		displayName = entity.displayName,
		timezone = ZoneId.of(entity.timezone),
		createdAt = entity.createdAt,
		updatedAt = entity.updatedAt,
	)

	fun toEntity(domain: UserAccount): UserAccountEntity = UserAccountEntity(
		userId = domain.userId.value,
		email = domain.email,
		displayName = domain.displayName,
		timezone = domain.timezone.id,
		createdAt = domain.createdAt,
		updatedAt = domain.updatedAt,
	)

	fun toDomain(entity: UserAuthIdentityEntity): UserAuthIdentity = UserAuthIdentity(
		identityId = entity.identityId,
		userId = UserId(entity.userId),
		provider = entity.provider,
		issuer = entity.issuer,
		externalSubject = entity.externalSubject,
		emailAtLinkTime = entity.emailAtLinkTime,
		createdAt = entity.createdAt,
		lastLoginAt = entity.lastLoginAt,
	)

	fun toEntity(domain: UserAuthIdentity): UserAuthIdentityEntity = UserAuthIdentityEntity(
		identityId = domain.identityId,
		userId = domain.userId.value,
		provider = domain.provider,
		issuer = domain.issuer,
		externalSubject = domain.externalSubject,
		emailAtLinkTime = domain.emailAtLinkTime,
		createdAt = domain.createdAt,
		lastLoginAt = domain.lastLoginAt,
	)
}
