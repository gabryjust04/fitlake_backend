package com.fitlake.user.application

import com.fitlake.shared.application.TransactionExecutor
import com.fitlake.shared.application.elapsedMilliseconds
import com.fitlake.user.application.port.UserAccountRepository
import com.fitlake.user.application.port.UserAuthIdentityRepository
import com.fitlake.user.domain.UserAccount
import com.fitlake.user.domain.UserAuthIdentity
import com.fitlake.user.domain.UserId
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import java.util.UUID

@Service
class UserProvisioningService(
	private val userAccountRepository: UserAccountRepository,
	private val userAuthIdentityRepository: UserAuthIdentityRepository,
	private val transactionExecutor: TransactionExecutor,
	private val clock: Clock,
	private val defaultUserTimezone: ZoneId,
) {
	fun provision(command: ProvisionUserCommand): UserAccount {
		val startedAtNanos = System.nanoTime()
		validate(command)

		val result = try {
			transactionExecutor.required { resolveOrCreate(command) }
		} catch (conflict: AuthIdentityConflictException) {
			transactionExecutor.required {
				ProvisioningResult(
					account = resolveExisting(command) ?: throw UserProvisioningException(
						"Authentication identity conflict could not be resolved",
						conflict,
					),
					created = false,
				)
			}
		}
		if (result.created) {
			logger.atInfo()
				.addKeyValue("event", "user_account_provisioned")
				.addKeyValue("outcome", "success")
				.addKeyValue("authProvider", command.provider.name.lowercase(Locale.ROOT))
				.addKeyValue("userRef", result.account.userId.value)
				.addKeyValue("durationMs", elapsedMilliseconds(startedAtNanos))
				.log("Internal user account provisioned")
		}
		return result.account
	}

	private fun resolveOrCreate(command: ProvisionUserCommand): ProvisioningResult {
		resolveExisting(command)?.let { return ProvisioningResult(it, created = false) }

		val now = clock.instant()
		val userAccount = userAccountRepository.save(
			UserAccount(
				userId = UserId(UUID.randomUUID()),
				email = command.email.normalizedOrNull(),
				displayName = command.displayName.normalizedOrNull(),
				timezone = defaultUserTimezone,
				createdAt = now,
				updatedAt = now,
			),
		)

		userAuthIdentityRepository.save(
			UserAuthIdentity(
				identityId = UUID.randomUUID(),
				userId = userAccount.userId,
				provider = command.provider,
				issuer = command.issuer,
				externalSubject = command.externalSubject,
				emailAtLinkTime = command.email.normalizedOrNull(),
				createdAt = now,
				lastLoginAt = now,
			),
		)

		return ProvisioningResult(userAccount, created = true)
	}

	private fun resolveExisting(command: ProvisionUserCommand): UserAccount? {
		val identity = userAuthIdentityRepository.findByIssuerAndExternalSubject(
			command.issuer,
			command.externalSubject,
		) ?: return null

		val account = userAccountRepository.findById(identity.userId)
			?: throw UserAccountNotFoundException(identity.userId)

		val now = clock.instant()
		val synchronizedAccount = synchronizeEmail(account, command.email, now)
		userAuthIdentityRepository.save(identity.recordLogin(now))
		return synchronizedAccount
	}

	private fun synchronizeEmail(
		account: UserAccount,
		providerEmail: String?,
		at: Instant,
	): UserAccount {
		val normalizedEmail = providerEmail.normalizedOrNull() ?: return account
		if (normalizedEmail == account.email) {
			return account
		}

		return userAccountRepository.save(
			account.copy(
				email = normalizedEmail,
				updatedAt = maxOf(account.updatedAt, at),
			),
		)
	}

	private fun validate(command: ProvisionUserCommand) {
		require(command.issuer.isNotBlank()) { "Issuer must not be blank" }
		require(command.externalSubject.isNotBlank()) { "External subject must not be blank" }
	}

	private fun String?.normalizedOrNull(): String? = this?.trim()?.takeIf(String::isNotEmpty)

	private data class ProvisioningResult(
		val account: UserAccount,
		val created: Boolean,
	)

	private companion object {
		val logger = LoggerFactory.getLogger(UserProvisioningService::class.java)
	}
}
