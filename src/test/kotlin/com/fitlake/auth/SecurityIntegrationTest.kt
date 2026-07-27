package com.fitlake.auth

import com.fitlake.auth.adapter.MeController
import com.fitlake.auth.infrastructure.RestAccessDeniedHandler
import com.fitlake.auth.infrastructure.RestAuthenticationEntryPoint
import com.fitlake.auth.infrastructure.SecurityConfig
import com.fitlake.auth.infrastructure.SecurityCurrentUserProvider
import com.fitlake.auth.infrastructure.OpenApiConfig
import com.fitlake.auth.infrastructure.firebase.FirebaseAuthenticationFilter
import com.fitlake.auth.infrastructure.firebase.FirebaseTokenClaims
import com.fitlake.auth.infrastructure.firebase.FirebaseTokenVerificationException
import com.fitlake.auth.infrastructure.firebase.FirebaseTokenVerifier
import com.fitlake.support.ImmediateTransactionExecutor
import com.fitlake.support.InMemoryUserAccountRepository
import com.fitlake.support.InMemoryUserAuthIdentityRepository
import com.fitlake.user.application.UserProvisioningService
import com.fitlake.user.application.UserQueryService
import org.junit.jupiter.api.Test
import org.springframework.boot.SpringBootConfiguration
import org.springframework.boot.autoconfigure.EnableAutoConfiguration
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import java.time.Clock
import java.time.Instant
import java.time.ZoneId

@SpringBootTest(
	classes = [SecurityIntegrationTest.TestApplication::class],
	properties = [
		"OPENAI_API_KEY=test-only",
		"FIREBASE_PROJECT_ID=fitlake-test",
		"management.endpoints.web.exposure.include=health",
		"spring.ai.model.chat=none",
	],
)
@AutoConfigureMockMvc
class SecurityIntegrationTest @Autowired constructor(
	private val mockMvc: MockMvc,
) {
	@Test
	fun `health endpoint is public`() {
		mockMvc.get("/actuator/health")
			.andExpect {
				status { isOk() }
			}
	}

	@Test
	fun `Swagger UI is public`() {
		mockMvc.get("/swagger-ui.html")
			.andExpect {
				status { is3xxRedirection() }
			}
	}

	@Test
	fun `OpenAPI document is public and describes api me`() {
		mockMvc.get("/v3/api-docs")
			.andExpect {
				status { isOk() }
				jsonPath("$.info.title") { value("FitLake API") }
				jsonPath("$.paths['/api/me']") { exists() }
				jsonPath("$.components.securitySchemes.firebaseBearer") { exists() }
			}
	}

	@Test
	fun `api me rejects missing token`() {
		mockMvc.get("/api/me")
			.andExpect {
				status { isUnauthorized() }
				jsonPath("$.error") { value("unauthorized") }
			}
	}

	@Test
	fun `api me rejects invalid token`() {
		mockMvc.get("/api/me") {
			header("Authorization", "Bearer invalid-token")
		}.andExpect {
			status { isUnauthorized() }
			jsonPath("$.error") { value("unauthorized") }
		}
	}

	@Test
	fun `api me returns the provisioned internal profile for a valid token`() {
		mockMvc.get("/api/me") {
			header("Authorization", "Bearer valid-token")
		}.andExpect {
			status { isOk() }
			jsonPath("$.userId") { isNotEmpty() }
			jsonPath("$.email") { value("user@example.com") }
			jsonPath("$.displayName") { value("Andrea") }
			jsonPath("$.timezone") { value("Europe/Rome") }
		}
	}

	@SpringBootConfiguration
	@EnableAutoConfiguration(
		excludeName = [
			"org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration",
			"org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration",
			"org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration",
		],
	)
	@Import(
		SecurityConfig::class,
		OpenApiConfig::class,
		RestAuthenticationEntryPoint::class,
		RestAccessDeniedHandler::class,
		FirebaseAuthenticationFilter::class,
		SecurityCurrentUserProvider::class,
		MeController::class,
		TestBeans::class,
	)
	class TestApplication

	@TestConfiguration(proxyBeanMethods = false)
	class TestBeans {
		@Bean
		fun tokenVerifier(): FirebaseTokenVerifier = FirebaseTokenVerifier { token ->
			if (token != "valid-token") {
				throw FirebaseTokenVerificationException()
			}
			FirebaseTokenClaims(
				issuer = "https://securetoken.google.com/fitlake-test",
				subject = "firebase-uid",
				email = "user@example.com",
				emailVerified = true,
				displayName = "Andrea",
			)
		}

		@Bean
		fun userAccountRepository() = InMemoryUserAccountRepository()

		@Bean
		fun userAuthIdentityRepository() = InMemoryUserAuthIdentityRepository()

		@Bean
		fun userProvisioningService(
			userAccountRepository: InMemoryUserAccountRepository,
			userAuthIdentityRepository: InMemoryUserAuthIdentityRepository,
		) = UserProvisioningService(
			userAccountRepository = userAccountRepository,
			userAuthIdentityRepository = userAuthIdentityRepository,
			transactionExecutor = ImmediateTransactionExecutor,
			clock = Clock.fixed(Instant.parse("2026-01-01T10:00:00Z"), ZoneId.of("UTC")),
			defaultUserTimezone = ZoneId.of("Europe/Rome"),
		)

		@Bean
		fun userQueryService(userAccountRepository: InMemoryUserAccountRepository) =
			UserQueryService(userAccountRepository)
	}
}
