package com.fitlake.food.adapter.rest

import com.fitlake.auth.infrastructure.OpenApiConfig
import com.fitlake.auth.infrastructure.RestAccessDeniedHandler
import com.fitlake.auth.infrastructure.RestAuthenticationEntryPoint
import com.fitlake.auth.infrastructure.SecurityConfig
import com.fitlake.auth.infrastructure.SecurityCurrentUserProvider
import com.fitlake.auth.infrastructure.firebase.FirebaseAuthenticationFilter
import com.fitlake.auth.infrastructure.firebase.FirebaseTokenClaims
import com.fitlake.auth.infrastructure.firebase.FirebaseTokenVerificationException
import com.fitlake.auth.infrastructure.firebase.FirebaseTokenVerifier
import com.fitlake.food.application.UserFoodSearchService
import com.fitlake.food.application.UserFoodService
import com.fitlake.support.ImmediateTransactionExecutor
import com.fitlake.support.InMemoryUserAccountRepository
import com.fitlake.support.InMemoryUserAuthIdentityRepository
import com.fitlake.support.InMemoryUserFoodRepository
import com.fitlake.user.application.UserProvisioningService
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.SpringBootConfiguration
import org.springframework.boot.autoconfigure.EnableAutoConfiguration
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockHttpServletRequestDsl
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.delete
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.patch
import org.springframework.test.web.servlet.post
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import kotlin.test.assertEquals

@SpringBootTest(
	classes = [UserFoodRestIntegrationTest.TestApplication::class],
	properties = [
		"OPENAI_API_KEY=test-only",
		"FIREBASE_PROJECT_ID=fitlake-test",
		"spring.ai.model.chat=none",
	],
)
@AutoConfigureMockMvc
class UserFoodRestIntegrationTest @Autowired constructor(
	private val mockMvc: MockMvc,
	private val foods: InMemoryUserFoodRepository,
	private val users: InMemoryUserAccountRepository,
	private val identities: InMemoryUserAuthIdentityRepository,
) {
	@BeforeEach
	fun reset() {
		foods.clear()
		users.clear()
		identities.clear()
	}

	@Test
	fun `food endpoints require authentication`() {
		mockMvc.get("/api/me/foods").andExpect {
			status { isUnauthorized() }
			jsonPath("$.error") { value("unauthorized") }
		}
	}

	@Test
	fun `authenticated user can create read list update search and delete a private food`() {
		val created = createFood("valid-a").andExpect {
			status { isCreated() }
			jsonPath("$.name") { value("My usual Greek yogurt") }
			jsonPath("$.aliases[0]") { value("my yogurt") }
			jsonPath("$.nutritionBasis.unit") { value("GRAM") }
			jsonPath("$.nutrients.proteinGrams") { value(9.5) }
			jsonPath("$.source.estimated") { value(false) }
			jsonPath("$.userId") { doesNotExist() }
		}
		val foodId = foodId(created.andReturn().response.contentAsString)
		val provisionedIdentity = requireNotNull(
			identities.findByIssuerAndExternalSubject(
				"https://securetoken.google.com/fitlake-test",
				"firebase-uid-valid-a",
			),
		)
		assertEquals(provisionedIdentity.userId, foods.all().single().userId)

		mockMvc.get("/api/me/foods/$foodId") { auth("valid-a") }.andExpect {
			status { isOk() }
			jsonPath("$.foodId") { value(foodId) }
		}
		mockMvc.get("/api/me/foods?page=0&size=10&sort=NAME_ASC") { auth("valid-a") }.andExpect {
			status { isOk() }
			jsonPath("$.totalElements") { value(1) }
			jsonPath("$.items[0].foodId") { value(foodId) }
		}
		mockMvc.get("/api/me/foods/search") {
			auth("valid-a")
			param("query", "my yogurt")
		}.andExpect {
			status { isOk() }
			jsonPath("$.results[0].matchedBy") { value("EXACT_ALIAS") }
		}

		mockMvc.patch("/api/me/foods/$foodId") {
			auth("valid-a")
			contentType = MediaType.APPLICATION_JSON
			content = validBody(name = "Yògurt updated", aliases = "[\"post workout\"]")
		}.andExpect {
			status { isOk() }
			jsonPath("$.name") { value("Yògurt updated") }
			jsonPath("$.aliases[0]") { value("post workout") }
		}

		mockMvc.delete("/api/me/foods/$foodId") { auth("valid-a") }.andExpect { status { isNoContent() } }
		mockMvc.get("/api/me/foods/$foodId") { auth("valid-a") }.andExpect { status { isNotFound() } }
		mockMvc.get("/api/me/foods") { auth("valid-a") }.andExpect {
			status { isOk() }
			jsonPath("$.totalElements") { value(0) }
		}
		mockMvc.get("/api/me/foods/search") {
			auth("valid-a")
			param("query", "post workout")
		}.andExpect {
			status { isOk() }
			jsonPath("$.results.length()") { value(0) }
		}
		mockMvc.delete("/api/me/foods/$foodId") { auth("valid-a") }.andExpect { status { isNotFound() } }
	}

	@Test
	fun `foreign foods are hidden from every owned resource operation`() {
		val foodId = foodId(createFood("valid-a").andReturn().response.contentAsString)

		mockMvc.get("/api/me/foods/$foodId") { auth("valid-b") }.andExpect { status { isNotFound() } }
		mockMvc.patch("/api/me/foods/$foodId") {
			auth("valid-b")
			contentType = MediaType.APPLICATION_JSON
			content = validBody()
		}.andExpect { status { isNotFound() } }
		mockMvc.delete("/api/me/foods/$foodId") { auth("valid-b") }.andExpect { status { isNotFound() } }
		mockMvc.get("/api/me/foods/search") {
			auth("valid-b")
			param("query", "my yogurt")
		}.andExpect {
			status { isOk() }
			jsonPath("$.results.length()") { value(0) }
		}
		mockMvc.get("/api/me/foods") { auth("valid-b") }.andExpect {
			status { isOk() }
			jsonPath("$.totalElements") { value(0) }
		}
	}

	@Test
	fun `alias and barcode conflicts are user scoped`() {
		createFood("valid-a").andExpect { status { isCreated() } }
		createFood("valid-a", name = "Other", barcode = "1234567890124").andExpect {
			status { isConflict() }
			jsonPath("$.error") { value("conflict") }
		}
		createFood("valid-b").andExpect { status { isCreated() } }
	}

	@Test
	fun `invalid nutrition serving aliases query and pagination return safe 400 responses`() {
		mockMvc.post("/api/me/foods") {
			auth("valid-a")
			contentType = MediaType.APPLICATION_JSON
			content = validBody().replace("\"caloriesKcal\": 62", "\"caloriesKcal\": -1")
		}.andExpect {
			status { isBadRequest() }
			jsonPath("$.error") { value("validation_error") }
		}
		mockMvc.post("/api/me/foods") {
			auth("valid-a")
			contentType = MediaType.APPLICATION_JSON
			content = validBody().replace("\"proteinGrams\": 9.5", "\"proteinGrams\": 9.1234567")
		}.andExpect {
			status { isBadRequest() }
			jsonPath("$.error") { value("validation_error") }
			jsonPath("$.fieldErrors['nutrients.proteinGrams']") { exists() }
		}
		mockMvc.post("/api/me/foods") {
			auth("valid-a")
			contentType = MediaType.APPLICATION_JSON
			content = validBody().replace("\"amount\": 100", "\"amount\": 0")
		}.andExpect { status { isBadRequest() } }
		mockMvc.post("/api/me/foods") {
			auth("valid-a")
			contentType = MediaType.APPLICATION_JSON
			content = validBody(aliases = "[\"my yogurt\", \"MY-YOGURT\"]")
		}.andExpect { status { isBadRequest() } }
		mockMvc.post("/api/me/foods") {
			auth("valid-a")
			contentType = MediaType.APPLICATION_JSON
			content = validBody().replace(
				"\"defaultServing\": {\"amount\": 170, \"unit\": \"GRAM\"}",
				"\"defaultServing\": {\"amount\": 1, \"unit\": \"PIECE\"}",
			)
		}.andExpect { status { isBadRequest() } }
		mockMvc.post("/api/me/foods") {
			auth("valid-a")
			contentType = MediaType.APPLICATION_JSON
			content = validBody().replace("\"unit\": \"GRAM\"", "\"unit\": \"OUNCE\"")
		}.andExpect {
			status { isBadRequest() }
			jsonPath("$.error") { value("invalid_request") }
		}
		mockMvc.get("/api/me/foods/search?query=a") { auth("valid-a") }.andExpect { status { isBadRequest() } }
		mockMvc.get("/api/me/foods/search") { auth("valid-a") }.andExpect {
			status { isBadRequest() }
			jsonPath("$.error") { value("invalid_request") }
		}
		mockMvc.get("/api/me/foods?page=-1") { auth("valid-a") }.andExpect { status { isBadRequest() } }
		mockMvc.get("/api/me/foods?size=101") { auth("valid-a") }.andExpect { status { isBadRequest() } }
		mockMvc.get("/api/me/foods?sort=RANDOM") { auth("valid-a") }.andExpect { status { isBadRequest() } }
	}

	@Test
	fun `OpenAPI exposes CRUD search and replacement semantics`() {
		mockMvc.get("/v3/api-docs").andExpect {
			status { isOk() }
			jsonPath("$.paths['/api/me/foods']") { exists() }
			jsonPath("$.paths['/api/me/foods/search']") { exists() }
			jsonPath("$.paths['/api/me/foods/{foodId}']") { exists() }
			jsonPath("$.paths['/api/me/foods/{foodId}'].patch.description") {
				value(org.hamcrest.Matchers.containsString("full replacement semantics"))
			}
			content { string(org.hamcrest.Matchers.containsString("Product label per 100 grams")) }
			content { string(org.hamcrest.Matchers.containsString("Nutrition per piece")) }
			content { string(org.hamcrest.Matchers.containsString("my yogurth")) }
		}
	}

	private fun createFood(token: String, name: String = "My usual Greek yogurt", barcode: String = "1234567890123") =
		mockMvc.post("/api/me/foods") {
			auth(token)
			contentType = MediaType.APPLICATION_JSON
			content = validBody(name, barcode)
		}

	private fun validBody(
		name: String = "My usual Greek yogurt",
		barcode: String = "1234567890123",
		aliases: String = "[\"my yogurt\", \"breakfast yogurt\"]",
	) = """
		{
		  "name": "$name",
		  "brand": "Example Brand",
		  "barcode": "$barcode",
		  "description": "Copied from a product label",
		  "aliases": $aliases,
		  "nutritionBasis": {"amount": 100, "unit": "GRAM"},
		  "nutrients": {
		    "caloriesKcal": 62,
		    "proteinGrams": 9.5,
		    "carbohydratesGrams": 4.1,
		    "fatGrams": 0.2,
		    "sugarsGrams": 4.1,
		    "sodiumMilligrams": 40
		  },
		  "defaultServing": {"amount": 170, "unit": "GRAM"},
		  "source": {"type": "PRODUCT_LABEL", "notes": "Copied manually"}
		}
	""".trimIndent()

	private fun foodId(response: String): String =
		requireNotNull(Regex("\"foodId\":\"([^\"]+)\"").find(response)?.groupValues?.get(1))

	private fun MockHttpServletRequestDsl.auth(token: String) {
		header("Authorization", "Bearer $token")
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
		UserFoodController::class,
		UserFoodApiExceptionHandler::class,
		UserFoodService::class,
		UserFoodSearchService::class,
		TestBeans::class,
	)
	class TestApplication

	@TestConfiguration(proxyBeanMethods = false)
	class TestBeans {
		@Bean
		fun tokenVerifier(): FirebaseTokenVerifier = FirebaseTokenVerifier { token ->
			if (token != "valid-a" && token != "valid-b") throw FirebaseTokenVerificationException()
			FirebaseTokenClaims(
				issuer = "https://securetoken.google.com/fitlake-test",
				subject = "firebase-uid-$token",
				email = "$token@example.com",
				emailVerified = true,
				displayName = token,
			)
		}

		@Bean fun userAccounts() = InMemoryUserAccountRepository()
		@Bean fun authIdentities() = InMemoryUserAuthIdentityRepository()
		@Bean fun userFoods() = InMemoryUserFoodRepository()
		@Bean fun clock(): Clock = Clock.fixed(Instant.parse("2026-07-31T12:00:00Z"), ZoneId.of("UTC"))
		@Bean fun transactionExecutor() = ImmediateTransactionExecutor

		@Bean
		fun userProvisioningService(
			userAccounts: InMemoryUserAccountRepository,
			authIdentities: InMemoryUserAuthIdentityRepository,
			clock: Clock,
		) = UserProvisioningService(
			userAccountRepository = userAccounts,
			userAuthIdentityRepository = authIdentities,
			transactionExecutor = ImmediateTransactionExecutor,
			clock = clock,
			defaultUserTimezone = ZoneId.of("Europe/Rome"),
		)
	}
}
