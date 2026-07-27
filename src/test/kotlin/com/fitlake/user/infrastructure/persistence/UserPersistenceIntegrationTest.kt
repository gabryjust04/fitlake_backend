package com.fitlake.user.infrastructure.persistence

import com.fitlake.user.application.AuthIdentityConflictException
import com.fitlake.user.domain.AuthProvider
import com.fitlake.user.domain.UserAccount
import com.fitlake.user.domain.UserAuthIdentity
import com.fitlake.user.domain.UserId
import com.fitlake.user.infrastructure.persistence.mapper.UserPersistenceMapper
import com.fitlake.user.infrastructure.persistence.repository.JpaUserAccountRepositoryAdapter
import com.fitlake.user.infrastructure.persistence.repository.JpaUserAuthIdentityRepositoryAdapter
import org.junit.jupiter.api.Test
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull

@DataJpaTest(
	properties = [
		"spring.jpa.hibernate.ddl-auto=validate",
		"spring.flyway.enabled=true",
	],
)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers(disabledWithoutDocker = true)
@Import(
	UserPersistenceMapper::class,
	JpaUserAccountRepositoryAdapter::class,
	JpaUserAuthIdentityRepositoryAdapter::class,
)
class UserPersistenceIntegrationTest @Autowired constructor(
	private val userAccounts: JpaUserAccountRepositoryAdapter,
	private val authIdentities: JpaUserAuthIdentityRepositoryAdapter,
	private val jdbcTemplate: JdbcTemplate,
) {
	@Test
	fun `Flyway creates the authentication identity table`() {
		val tableName = jdbcTemplate.queryForObject(
			"SELECT to_regclass('public.user_auth_identity')::text",
			String::class.java,
		)

		assertEquals("user_auth_identity", tableName)
	}

	@Test
	fun `identity lookup resolves the persisted internal user`() {
		val account = account()
		val identity = identity(userId = account.userId)
		userAccounts.save(account)
		authIdentities.save(identity)

		val resolvedIdentity = authIdentities.findByIssuerAndExternalSubject(
			identity.issuer,
			identity.externalSubject,
		)
		val resolvedAccount = resolvedIdentity?.let { userAccounts.findById(it.userId) }

		assertEquals(identity, resolvedIdentity)
		assertEquals(account, resolvedAccount)
		assertNotNull(resolvedAccount)
	}

	@Test
	fun `issuer and external subject are unique`() {
		val firstAccount = account()
		val secondAccount = account()
		userAccounts.save(firstAccount)
		userAccounts.save(secondAccount)
		val firstIdentity = identity(userId = firstAccount.userId)
		authIdentities.save(firstIdentity)

		assertFailsWith<AuthIdentityConflictException> {
			authIdentities.save(
				identity(
					userId = secondAccount.userId,
					issuer = firstIdentity.issuer,
					externalSubject = firstIdentity.externalSubject,
				),
			)
		}
	}

	@Test
	fun `one user cannot have two subjects for the same issuer`() {
		val account = account()
		userAccounts.save(account)
		authIdentities.save(identity(userId = account.userId, externalSubject = "uid-one"))

		assertFailsWith<AuthIdentityConflictException> {
			authIdentities.save(identity(userId = account.userId, externalSubject = "uid-two"))
		}
	}

	private fun account() = UserAccount(
		userId = UserId(UUID.randomUUID()),
		email = "user@example.com",
		displayName = "Andrea",
		timezone = ZoneId.of("Europe/Rome"),
		createdAt = Instant.parse("2026-01-01T10:00:00Z"),
		updatedAt = Instant.parse("2026-01-01T10:00:00Z"),
	)

	private fun identity(
		userId: UserId,
		issuer: String = "https://securetoken.google.com/fitlake-test",
		externalSubject: String = "firebase-uid-${UUID.randomUUID()}",
	) = UserAuthIdentity(
		identityId = UUID.randomUUID(),
		userId = userId,
		provider = AuthProvider.FIREBASE,
		issuer = issuer,
		externalSubject = externalSubject,
		emailAtLinkTime = "user@example.com",
		createdAt = Instant.parse("2026-01-01T10:00:00Z"),
		lastLoginAt = Instant.parse("2026-01-01T10:00:00Z"),
	)

	companion object {
		@Container
		@JvmStatic
		val postgres = PostgreSQLContainer("postgres:16-alpine")

		@DynamicPropertySource
		@JvmStatic
		fun postgresProperties(registry: DynamicPropertyRegistry) {
			registry.add("spring.datasource.url", postgres::getJdbcUrl)
			registry.add("spring.datasource.username", postgres::getUsername)
			registry.add("spring.datasource.password", postgres::getPassword)
		}
	}
}
