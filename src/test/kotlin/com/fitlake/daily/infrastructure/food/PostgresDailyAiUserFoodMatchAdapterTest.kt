package com.fitlake.daily.infrastructure.food

import com.fitlake.daily.application.port.DailyAiUserFoodMatchPort
import com.fitlake.daily.application.port.DailyAiUserFoodMatchResult
import com.fitlake.food.domain.FoodUnit
import com.fitlake.food.domain.NutrientValues
import com.fitlake.food.domain.NutritionBasis
import com.fitlake.food.domain.NutritionSource
import com.fitlake.food.domain.NutritionSourceType
import com.fitlake.food.domain.UnitConversions
import com.fitlake.food.domain.UserFood
import com.fitlake.food.domain.UserFoodDefinition
import com.fitlake.food.infrastructure.persistence.mapper.UserFoodPersistenceMapper
import com.fitlake.food.infrastructure.persistence.repository.JpaUserFoodRepositoryAdapter
import com.fitlake.shared.application.TransactionExecutor
import com.fitlake.user.domain.UserAccount
import com.fitlake.user.domain.UserId
import com.fitlake.user.infrastructure.SpringTransactionExecutor
import com.fitlake.user.infrastructure.persistence.mapper.UserPersistenceMapper
import com.fitlake.user.infrastructure.persistence.repository.JpaUserAccountRepositoryAdapter
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase
import org.springframework.context.annotation.Import
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import java.math.BigDecimal
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertIs

@DataJpaTest(
	properties = [
		"spring.jpa.hibernate.ddl-auto=validate",
		"spring.flyway.enabled=true",
	],
)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers(disabledWithoutDocker = true)
@Import(
	PostgresDailyAiUserFoodMatchAdapter::class,
	UserFoodPersistenceMapper::class,
	JpaUserFoodRepositoryAdapter::class,
	UserPersistenceMapper::class,
	JpaUserAccountRepositoryAdapter::class,
	SpringTransactionExecutor::class,
)
class PostgresDailyAiUserFoodMatchAdapterTest @Autowired constructor(
	private val matcher: DailyAiUserFoodMatchPort,
	private val foods: JpaUserFoodRepositoryAdapter,
	private val users: JpaUserAccountRepositoryAdapter,
	private val transactions: TransactionExecutor,
) {
	private val now = Instant.parse("2026-07-31T12:00:00Z")
	private var userA = UserId(UUID(0, 0))
	private var userB = UserId(UUID(0, 0))

	@BeforeEach
	fun createUsers() {
		userA = createUser("daily-ai-match-a")
		userB = createUser("daily-ai-match-b")
	}

	@Test
	fun `exact normalized name returns the complete active owned food`() {
		val food = createFood(userA, "Pollo grigliato", aliases = listOf("Pollo grigliato"))

		val match = assertIs<DailyAiUserFoodMatchResult.Unique>(
			matcher.match(userA, "  POLLO-GRIGLIÀTO "),
		)

		assertEquals(food.foodId.value, match.food.userFoodId)
		assertEquals(food.name, match.food.displayName)
		assertEquals(food.version, match.food.version)
		assertEquals(food.updatedAt, match.food.updatedAt)
		assertEquals(food.nutrients.caloriesKcal, match.food.nutrientsPerBasis.caloriesKcal)
	}

	@Test
	fun `exact normalized alias returns its active owned food`() {
		val food = createFood(userA, "Greek yogurt", aliases = listOf("Il mio yogurt"))

		val match = assertIs<DailyAiUserFoodMatchResult.Unique>(
			matcher.match(userA, "IL-MIO YÒGURT"),
		)

		assertEquals(food.foodId.value, match.food.userFoodId)
	}

	@Test
	fun `exact name collisions and name alias collisions are ambiguous`() {
		createFood(userA, "Duplicate food")
		createFood(userA, "Duplicate food")
		createFood(userA, "Shared wording")
		createFood(userA, "Different food", aliases = listOf("Shared wording"))

		assertIs<DailyAiUserFoodMatchResult.Ambiguous>(matcher.match(userA, "duplicate food"))
		assertIs<DailyAiUserFoodMatchResult.Ambiguous>(matcher.match(userA, "shared wording"))
	}

	@Test
	fun `prefix and fuzzy similarities do not produce an automatic match`() {
		createFood(userA, "Greek yogurt", aliases = listOf("Breakfast yogurt"))

		assertIs<DailyAiUserFoodMatchResult.None>(matcher.match(userA, "Greek"))
		assertIs<DailyAiUserFoodMatchResult.None>(matcher.match(userA, "Greek yogurth"))
		assertIs<DailyAiUserFoodMatchResult.None>(matcher.match(userA, "Breakfast"))
	}

	@Test
	fun `foreign and deleted exact foods are excluded`() {
		val foreign = createFood(userB, "Private pudding", aliases = listOf("Secret pudding"))
		val deleted = createFood(userA, "Old pudding", aliases = listOf("Deleted pudding"))
		transactions.required { foods.save(deleted.softDelete(now.plusSeconds(1))) }

		assertIs<DailyAiUserFoodMatchResult.None>(matcher.match(userA, foreign.name))
		assertIs<DailyAiUserFoodMatchResult.None>(matcher.match(userA, "Secret pudding"))
		assertIs<DailyAiUserFoodMatchResult.None>(matcher.match(userA, deleted.name))
		assertIs<DailyAiUserFoodMatchResult.None>(matcher.match(userA, "Deleted pudding"))
		assertEquals(
			foreign.foodId.value,
			assertIs<DailyAiUserFoodMatchResult.Unique>(matcher.match(userB, foreign.name)).food.userFoodId,
		)
	}

	private fun createUser(prefix: String): UserId = transactions.required {
		val userId = UserId(UUID.randomUUID())
		users.save(
			UserAccount(
				userId = userId,
				email = "$prefix-${userId.value}@example.com",
				displayName = "AI food match integration",
				timezone = ZoneId.of("Europe/Rome"),
				createdAt = now,
				updatedAt = now,
			),
		)
		userId
	}

	private fun createFood(
		userId: UserId,
		name: String,
		aliases: List<String> = emptyList(),
	): UserFood = transactions.required {
		foods.save(
			UserFood.create(
				userId = userId,
				definition = UserFoodDefinition.from(
					name = name,
					brand = "Test brand",
					barcode = null,
					description = null,
					aliases = aliases,
					nutritionBasis = NutritionBasis(BigDecimal("100"), FoodUnit.GRAM),
					nutrients = NutrientValues(
						caloriesKcal = BigDecimal("165"),
						proteinGrams = BigDecimal("31"),
						carbohydratesGrams = BigDecimal.ZERO,
						fatGrams = BigDecimal("3.6"),
					),
					defaultServing = null,
					conversions = UnitConversions.NONE,
					source = NutritionSource(NutritionSourceType.USER_ENTERED),
				),
				at = now,
			),
		)
	}

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
