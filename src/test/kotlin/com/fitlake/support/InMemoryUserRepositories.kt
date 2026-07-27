package com.fitlake.support

import com.fitlake.user.application.AuthIdentityConflictException
import com.fitlake.user.application.port.TransactionExecutor
import com.fitlake.user.application.port.UserAccountRepository
import com.fitlake.user.application.port.UserAuthIdentityRepository
import com.fitlake.user.domain.UserAccount
import com.fitlake.user.domain.UserAuthIdentity
import com.fitlake.user.domain.UserId
import java.util.concurrent.ConcurrentHashMap

class InMemoryUserAccountRepository : UserAccountRepository {
	private val accounts = ConcurrentHashMap<UserId, UserAccount>()

	override fun findById(userId: UserId): UserAccount? = accounts[userId]

	override fun save(userAccount: UserAccount): UserAccount = userAccount.also {
		accounts[it.userId] = it
	}

	fun count(): Int = accounts.size

	fun clear() = accounts.clear()
}

class InMemoryUserAuthIdentityRepository : UserAuthIdentityRepository {
	private val identities = ConcurrentHashMap<Pair<String, String>, UserAuthIdentity>()

	@Volatile
	var conflictOnNextInsert: Boolean = false

	override fun findByIssuerAndExternalSubject(
		issuer: String,
		externalSubject: String,
	): UserAuthIdentity? = identities[issuer to externalSubject]

	@Synchronized
	override fun save(identity: UserAuthIdentity): UserAuthIdentity {
		val key = identity.issuer to identity.externalSubject
		val existing = identities[key]
		val sameUserAndIssuer = identities.values.firstOrNull {
			it.userId == identity.userId && it.issuer == identity.issuer && it.identityId != identity.identityId
		}
		if (
			sameUserAndIssuer != null ||
			(existing != null && existing.identityId != identity.identityId)
		) {
			throw conflict()
		}
		if (conflictOnNextInsert && existing == null) {
			conflictOnNextInsert = false
			identities[key] = identity
			throw conflict()
		}
		identities[key] = identity
		return identity
	}

	fun count(): Int = identities.size

	fun clear() {
		identities.clear()
		conflictOnNextInsert = false
	}

	private fun conflict() = AuthIdentityConflictException(IllegalStateException("simulated unique constraint"))
}

object ImmediateTransactionExecutor : TransactionExecutor {
	override fun <T : Any> required(action: () -> T): T = action()
}
