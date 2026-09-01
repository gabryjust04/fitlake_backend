package com.fitlake.food.infrastructure.persistence

import com.fitlake.food.application.DefaultServingInput
import com.fitlake.food.application.NutrientValuesInput
import com.fitlake.food.application.NutritionBasisInput
import com.fitlake.food.application.NutritionSourceInput
import com.fitlake.food.application.UnitConversionsInput
import com.fitlake.food.application.UserFoodDefinitionInput
import com.fitlake.food.application.UserFoodMatchType
import com.fitlake.food.application.UserFoodPageQuery
import com.fitlake.food.application.UserFoodSearchService
import com.fitlake.food.application.UserFoodService
import com.fitlake.food.application.UserFoodSort
import com.fitlake.food.domain.FoodUnit
import com.fitlake.food.domain.NutritionSourceType
import com.fitlake.food.domain.UserFood
import com.fitlake.food.infrastructure.persistence.mapper.UserFoodPersistenceMapper
import com.fitlake.food.infrastructure.persistence.repository.JpaUserFoodRepositoryAdapter
import com.fitlake.food.infrastructure.persistence.repository.PostgresUserFoodSearchAdapter
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
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.transaction.PlatformTransactionManager
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@DataJpaTest(
	properties = [
		"spring.jpa.hibernate.ddl-auto=validate",
		"spring.flyway.enabled=true",
	],
)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers(disabledWithoutDocker = true)
@Import(
	UserFoodPersistenceMapper::class,
	JpaUserFoodRepositoryAdapter::class,
	PostgresUserFoodSearchAdapter::class,
	UserPersistenceMapper::class,
	JpaUserAccountRepositoryAdapter::class,
)
class UserFoodPersistenceIntegrationTest @Autowired constructor(
	private val foods: JpaUserFoodRepositoryAdapter,
	private val searchAdapter: PostgresUserFoodSearchAdapter,
	private val users: JpaUserAccountRepositoryAdapter,
	private val jdbcTemplate: JdbcTemplate,
	transactionManager: PlatformTransactionManager,
) {
	private val now = Instant.parse("2026-07-31T12:00:00Z")
	private val clock = Clock.fixed(now, ZoneId.of("UTC"))
	private val transactions: TransactionExecutor = SpringTransactionExecutor(transactionManager)
	private val service = UserFoodService(foods, transactions, clock)
	private val search = UserFoodSearchService(searchAdapter, transactions)
	private var userA: UserId = UserId(UUID(0, 0))
	private var userB: UserId = UserId(UUID(0, 0))

	@BeforeEach
	fun createUsers() {
		userA = createUser("food-a@example.com")
		userB = createUser("food-b@example.com")
	}

	@Test
	fun `migration enables pg trgm and creates expected partial and trigram indexes`() {
		assertEquals(
			1,
			jdbcTemplate.queryForObject(
				"SELECT COUNT(*) FROM pg_extension WHERE extname = 'pg_trgm'",
				Int::class.java,
			),
		)
		assertNotNull(jdbcTemplate.queryForObject("SELECT to_regclass('user_food')", String::class.java))
		assertNotNull(jdbcTemplate.queryForObject("SELECT to_regclass('user_food_alias')", String::class.java))
		val indexes = jdbcTemplate.queryForList(
			"SELECT indexdef FROM pg_indexes WHERE tablename IN ('user_food', 'user_food_alias')",
			String::class.java,
		).joinToString("\n")
		assertTrue(indexes.contains("gin_trgm_ops"))
		assertTrue(indexes.contains("WHERE (deleted_at IS NULL)"))
		assertTrue(indexes.contains("uq_user_food_active_barcode"))
		assertTrue(indexes.contains("uq_user_food_alias_active_normalized"))
		assertTrue(indexes.contains("idx_user_food_active_name_prefix"))
		assertTrue(indexes.contains("idx_user_food_alias_active_prefix"))
		assertTrue(indexes.contains("idx_user_food_active_name_trgm"))
		assertTrue(indexes.contains("idx_user_food_alias_active_trgm"))
	}

	@Test
	fun `food and aliases round trip with decimal precision and nullable unknown nutrients`() {
		val created = service.create(
			userA,
			input(
				name = "Greek yogurt",
				aliases = listOf("my yogurt", "breakfast yogurt"),
				protein = BigDecimal("9.123456"),
			),
		)

		val loaded = service.get(userA, created.foodId)

		assertEquals(BigDecimal("9.123456"), loaded.nutrients.proteinGrams)
		assertNull(loaded.nutrients.fiberGrams)
		assertEquals(listOf("breakfast yogurt", "my yogurt"), loaded.aliases.map { it.value })
		assertEquals(BigDecimal("170"), loaded.defaultServing?.amount)
		assertEquals(1, service.list(userA, UserFoodPageQuery()).totalElements)
	}

	@Test
	fun `all nutrition conversion and source fields round trip without converting unknowns to zero`() {
		val detailed = service.create(
			userA,
			UserFoodDefinitionInput(
				name = "Detailed biscuit",
				brand = "Bakery",
				barcode = "72345678",
				description = "Full persistence mapping",
				aliases = listOf("detailed snack"),
				nutritionBasis = NutritionBasisInput(BigDecimal("100.123456"), FoodUnit.GRAM),
				nutrients = NutrientValuesInput(
					caloriesKcal = BigDecimal("321.123456"),
					proteinGrams = BigDecimal("12.1"),
					carbohydratesGrams = BigDecimal("42.2"),
					fatGrams = BigDecimal("9.3"),
					fiberGrams = BigDecimal("4.4"),
					sugarsGrams = BigDecimal("5.5"),
					saturatedFatGrams = BigDecimal("2.6"),
					sodiumMilligrams = BigDecimal("140.7"),
					saltGrams = BigDecimal("0.8"),
				),
				defaultServing = DefaultServingInput(BigDecimal("2"), FoodUnit.PIECE),
				conversions = UnitConversionsInput(
					gramsPerPiece = BigDecimal("12.25"),
					gramsPerServing = BigDecimal("30.5"),
				),
				source = NutritionSourceInput(
					type = NutritionSourceType.EXTERNAL_DATABASE,
					provider = "Imported label archive",
					externalId = "food-42",
					notes = "Copied by the user",
					copiedAt = LocalDate.parse("2026-07-30"),
				),
			),
		)

		val loaded = service.get(userA, detailed.foodId)
		assertEquals("Bakery", loaded.brand)
		assertEquals("72345678", loaded.barcode)
		assertEquals("Full persistence mapping", loaded.description)
		assertEquals(BigDecimal("100.123456"), loaded.nutritionBasis.amount)
		assertEquals(detailed.nutrients, loaded.nutrients)
		assertEquals(BigDecimal("12.25"), loaded.conversions.gramsPerPiece)
		assertEquals(BigDecimal("30.5"), loaded.conversions.gramsPerServing)
		assertEquals("Imported label archive", loaded.source.provider)
		assertEquals("food-42", loaded.source.externalId)
		assertEquals("Copied by the user", loaded.source.notes)
		assertEquals(LocalDate.parse("2026-07-30"), loaded.source.copiedAt)

		val unknown = service.create(
			userA,
			UserFoodDefinitionInput(
				name = "Unknown drink",
				barcode = "72345679",
				aliases = listOf("unknown beverage"),
				nutritionBasis = NutritionBasisInput(BigDecimal("100"), FoodUnit.MILLILITER),
				nutrients = NutrientValuesInput(),
				defaultServing = DefaultServingInput(BigDecimal.ONE, FoodUnit.SERVING),
				conversions = UnitConversionsInput(
					millilitersPerPiece = BigDecimal("33"),
					millilitersPerServing = BigDecimal("250"),
				),
				source = NutritionSourceInput(NutritionSourceType.USER_ENTERED),
			),
		)
		val loadedUnknown = service.get(userA, unknown.foodId)
		assertEquals(NutrientValuesInput(), loadedUnknown.nutrients.let {
			NutrientValuesInput(
				it.caloriesKcal,
				it.proteinGrams,
				it.carbohydratesGrams,
				it.fatGrams,
				it.fiberGrams,
				it.sugarsGrams,
				it.saturatedFatGrams,
				it.sodiumMilligrams,
				it.saltGrams,
			)
		})
		assertEquals(BigDecimal("33"), loadedUnknown.conversions.millilitersPerPiece)
		assertEquals(BigDecimal("250"), loadedUnknown.conversions.millilitersPerServing)
	}

	@Test
	fun `search ranking is exact barcode then alias then name and remains user scoped`() {
		service.create(userA, input(name = "Barcode winner", barcode = "12345678", aliases = listOf("barcode winner")))
		service.create(userA, input(name = "Alias digits", barcode = "12345679", aliases = listOf("12345678")))
		service.create(userB, input(name = "Foreign exact", barcode = "12345678", aliases = listOf("12345678")))

		val barcodeResults = search.search(userA, "12345678", 10)

		assertEquals(UserFoodMatchType.EXACT_BARCODE, barcodeResults[0].matchedBy)
		assertEquals("Barcode winner", barcodeResults[0].name)
		assertEquals(UserFoodMatchType.EXACT_ALIAS, barcodeResults[1].matchedBy)
		assertTrue(barcodeResults.none { it.name == "Foreign exact" })

		service.create(userA, input(name = "My yogurt", barcode = "22345678", aliases = listOf("exact name helper")))
		service.create(userA, input(name = "Alias winner", barcode = "22345679", aliases = listOf("my yogurt")))
		val textResults = search.search(userA, "MY-YOGURT", 10)
		assertEquals(UserFoodMatchType.EXACT_ALIAS, textResults[0].matchedBy)
		assertEquals("Alias winner", textResults[0].name)
		assertEquals(UserFoodMatchType.EXACT_NAME, textResults[1].matchedBy)
	}

	@Test
	fun `prefix and trigram typo search rank aliases before names and respect limits`() {
		service.create(userA, input(name = "Greek prefix bowl", barcode = "32345678", aliases = listOf("other alias")))
		service.create(userA, input(name = "Prefix alias food", barcode = "32345679", aliases = listOf("greek breakfast")))
		service.create(userA, input(name = "Unrelated", barcode = "32345670", aliases = listOf("my yogurt")))

		val prefix = search.search(userA, "greek", 10)
		assertEquals(UserFoodMatchType.PREFIX_ALIAS, prefix[0].matchedBy)
		assertEquals(UserFoodMatchType.PREFIX_NAME, prefix[1].matchedBy)

		val typo = search.search(userA, "my yogurth", 1)
		assertEquals(1, typo.size)
		assertEquals(UserFoodMatchType.FUZZY_ALIAS, typo.single().matchedBy)
		assertEquals("my yogurt", typo.single().matchedText)
		assertTrue(typo.single().score in 0.3..1.0)
	}

	@Test
	fun `exact names outrank fuzzy aliases and fuzzy names are searchable`() {
		service.create(userA, input(name = "Protein yogurt", barcode = "42345678", aliases = listOf("exact helper")))
		service.create(userA, input(name = "Alias candidate", barcode = "42345679", aliases = listOf("protein yogrut")))
		service.create(userA, input(name = "Protein yogrut", barcode = "42345670", aliases = listOf("name helper")))

		val results = search.search(userA, "protein yogurt", 10)

		assertEquals(UserFoodMatchType.EXACT_NAME, results[0].matchedBy)
		assertEquals(UserFoodMatchType.FUZZY_ALIAS, results[1].matchedBy)
		assertEquals(UserFoodMatchType.FUZZY_NAME, results[2].matchedBy)
	}

	@Test
	fun `search deduplicates foods limits deterministically and filters tenants before limiting`() {
		service.create(userA, input(name = "My yogurt", barcode = "62345670", aliases = listOf("my yogurt")))
		val deduplicated = search.search(userA, "my yogurt", 10)
		assertEquals(1, deduplicated.size)
		assertEquals(UserFoodMatchType.EXACT_ALIAS, deduplicated.single().matchedBy)

		service.create(userA, input(name = "Owned numeric alias", barcode = "62345671", aliases = listOf("62345678")))
		service.create(userB, input(name = "Foreign barcode", barcode = "62345678", aliases = listOf("foreign numeric")))
		val tenantLimited = search.search(userA, "62345678", 1)
		assertEquals("Owned numeric alias", tenantLimited.single().name)
		assertEquals(UserFoodMatchType.EXACT_ALIAS, tenantLimited.single().matchedBy)

		service.create(userA, input(name = "Zulu prefix", barcode = "62345672", aliases = listOf("greek one")))
		service.create(userA, input(name = "Alpha prefix", barcode = "62345673", aliases = listOf("greek two")))
		service.create(userA, input(name = "Beta prefix", barcode = "62345674", aliases = listOf("greek six")))
		val limited = search.search(userA, "greek", 2)
		assertEquals(listOf("Alpha prefix", "Beta prefix"), limited.map { it.name })
		assertTrue(limited.all { it.matchedBy == UserFoodMatchType.PREFIX_ALIAS })
	}

	@Test
	fun `pagination is stable and alias replacement persists`() {
		service.create(userA, input(name = "Zulu food", barcode = "52345678", aliases = listOf("old zulu")))
		val alpha = service.create(userA, input(name = "Alpha food", barcode = "52345679", aliases = listOf("old alpha")))

		val firstPage = service.list(userA, UserFoodPageQuery(page = 0, size = 1))
		val secondPage = service.list(userA, UserFoodPageQuery(page = 1, size = 1))
		assertEquals(2, firstPage.totalElements)
		assertEquals("Alpha food", firstPage.items.single().name)
		assertEquals("Zulu food", secondPage.items.single().name)

		service.replace(
			userA,
			alpha.foodId,
			input(name = "Beta food", barcode = "52345679", aliases = listOf("new alpha")),
		)

		assertTrue(search.search(userA, "old alpha").none { it.matchedText == "old alpha" })
		assertEquals(UserFoodMatchType.EXACT_ALIAS, search.search(userA, "new alpha").single().matchedBy)
		assertEquals(listOf("new alpha"), service.get(userA, alpha.foodId).aliases.map { it.value })
	}

	@Test
	fun `all list sorts are stable paginated and user scoped`() {
		val alpha = UserFood.create(
			userA,
			input(name = "Alpha food", barcode = "82345670", aliases = listOf("alpha alias")).toDefinition(),
			now.minusSeconds(300),
		).copy(updatedAt = now.minusSeconds(10))
		val bravo = UserFood.create(
			userA,
			input(name = "Bravo food", barcode = "82345671", aliases = listOf("bravo alias")).toDefinition(),
			now.minusSeconds(200),
		).copy(updatedAt = now.minusSeconds(30))
		val charlie = UserFood.create(
			userA,
			input(name = "Charlie food", barcode = "82345672", aliases = listOf("charlie alias")).toDefinition(),
			now.minusSeconds(100),
		).copy(updatedAt = now.minusSeconds(20))
		listOf(alpha, bravo, charlie).forEach(foods::save)

		assertEquals(
			listOf("Alpha food", "Bravo food", "Charlie food"),
			service.list(userA, UserFoodPageQuery(size = 3, sort = UserFoodSort.NAME_ASC)).items.map { it.name },
		)
		assertEquals(
			listOf("Charlie food", "Bravo food", "Alpha food"),
			service.list(userA, UserFoodPageQuery(size = 3, sort = UserFoodSort.CREATED_AT_DESC)).items.map { it.name },
		)
		assertEquals(
			listOf("Alpha food", "Charlie food", "Bravo food"),
			service.list(userA, UserFoodPageQuery(size = 3, sort = UserFoodSort.UPDATED_AT_DESC)).items.map { it.name },
		)

		val pageZero = service.list(userA, UserFoodPageQuery(page = 0, size = 2))
		val pageOne = service.list(userA, UserFoodPageQuery(page = 1, size = 2))
		val pageBeyondEnd = service.list(userA, UserFoodPageQuery(page = 2, size = 2))
		assertEquals(3, pageZero.totalElements)
		assertEquals(2, pageZero.totalPages)
		assertEquals(2, pageZero.items.size)
		assertEquals(1, pageOne.items.size)
		assertTrue(pageZero.items.map { it.foodId }.intersect(pageOne.items.map { it.foodId }.toSet()).isEmpty())
		assertTrue(pageBeyondEnd.items.isEmpty())
		assertEquals(pageZero.items, service.list(userA, UserFoodPageQuery(page = 0, size = 2)).items)
		assertEquals(0, service.list(userB, UserFoodPageQuery()).totalElements)
	}

	@Test
	fun `full replacement clears optional fields and makes removed aliases and barcode reusable`() {
		val created = service.create(userA, input(name = "Original food", barcode = "92345670", aliases = listOf("old alias")))

		val replaced = service.replace(
			userA,
			created.foodId,
			UserFoodDefinitionInput(
				name = "Replacement food",
				aliases = emptyList(),
				nutritionBasis = NutritionBasisInput(BigDecimal.ONE, FoodUnit.PIECE),
				nutrients = NutrientValuesInput(),
				source = NutritionSourceInput(NutritionSourceType.USER_ENTERED),
			),
		)

		assertEquals(created.createdAt, replaced.createdAt)
		assertTrue(replaced.version > created.version)
		assertNull(replaced.brand)
		assertNull(replaced.barcode)
		assertNull(replaced.description)
		assertNull(replaced.defaultServing)
		assertTrue(replaced.aliases.isEmpty())
		assertTrue(search.search(userA, "old alias").isEmpty())
		assertEquals(
			0,
			jdbcTemplate.queryForObject(
				"SELECT COUNT(*) FROM user_food_alias WHERE user_food_id = ? AND deleted_at IS NULL",
				Int::class.java,
				created.foodId.value,
			),
		)
		assertEquals(
			1,
			jdbcTemplate.queryForObject(
				"SELECT COUNT(*) FROM user_food_alias WHERE user_food_id = ? AND deleted_at IS NOT NULL",
				Int::class.java,
				created.foodId.value,
			),
		)
		assertNotNull(service.create(userA, input(name = "Reuse", barcode = "92345670", aliases = listOf("old alias"))))
	}

	@Test
	fun `soft delete removes food and aliases from reads search and active uniqueness`() {
		val original = service.create(userA, input())
		service.softDelete(userA, original.foodId)

		assertNull(foods.findActiveByIdAndUserId(original.foodId, userA))
		assertTrue(search.search(userA, "my yogurt").isEmpty())
		val replacement = service.create(userA, input(name = "Replacement"))
		assertNotNull(replacement)
		assertEquals(
			0,
			jdbcTemplate.queryForObject(
				"SELECT COUNT(*) FROM user_food_alias WHERE user_food_id = ? AND deleted_at IS NULL",
				Int::class.java,
				original.foodId.value,
			),
		)
	}

	@Test
	fun `catalog operations never create Daily or AI state`() {
		val protectedTables = listOf(
			"daily_day",
			"daily_inbox_event",
			"daily_capture",
			"ai_interpretation_log",
			"daily_metrics",
		)
		val before = protectedTables.associateWith(::rowCount)

		val created = service.create(userA, input(name = "Independent food", barcode = "93345670", aliases = listOf("independent")))
		service.get(userA, created.foodId)
		service.list(userA, UserFoodPageQuery())
		search.search(userA, "independent")
		service.replace(
			userA,
			created.foodId,
			input(name = "Still independent", barcode = "93345670", aliases = listOf("still independent")),
		)
		service.softDelete(userA, created.foodId)

		assertEquals(before, protectedTables.associateWith(::rowCount))
	}

	@Test
	fun `database constraints reject invalid nutrition even outside the domain`() {
		val created = service.create(userA, input())

		assertFailsWith<DataIntegrityViolationException> {
			jdbcTemplate.update(
				"UPDATE user_food SET protein_grams = -1 WHERE user_food_id = ?",
				created.foodId.value,
			)
		}
	}

	@Test
	fun `database constraints reject an unconvertible default serving`() {
		val created = service.create(userA, input())

		assertFailsWith<DataIntegrityViolationException> {
			jdbcTemplate.update(
				"UPDATE user_food SET default_serving_unit = 'PIECE' WHERE user_food_id = ?",
				created.foodId.value,
			)
		}
	}

	@Test
	fun `database composite foreign key rejects aliases with another owner`() {
		val created = service.create(userA, input())

		assertFailsWith<DataIntegrityViolationException> {
			jdbcTemplate.update(
				"""
				INSERT INTO user_food_alias (
				    alias_id, user_food_id, user_id, alias, normalized_alias, created_at, deleted_at
				) VALUES (?, ?, ?, 'foreign owner', 'foreign owner', CURRENT_TIMESTAMP, NULL)
				""".trimIndent(),
				UUID.randomUUID(),
				created.foodId.value,
				userB.value,
			)
		}
	}

	private fun rowCount(table: String): Int {
		require(table in setOf("daily_day", "daily_inbox_event", "daily_capture", "ai_interpretation_log", "daily_metrics"))
		return requireNotNull(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM $table", Int::class.java))
	}

	private fun createUser(email: String): UserId {
		val userId = UserId(UUID.randomUUID())
		users.save(
			UserAccount(
				userId = userId,
				email = email,
				displayName = "Food test",
				timezone = ZoneId.of("Europe/Rome"),
				createdAt = now,
				updatedAt = now,
			),
		)
		return userId
	}

	private fun input(
		name: String = "Greek yogurt",
		barcode: String = "1234567890123",
		aliases: List<String> = listOf("my yogurt"),
		protein: BigDecimal = BigDecimal("9.5"),
	) = UserFoodDefinitionInput(
		name = name,
		brand = "Example",
		barcode = barcode,
		description = "Product label",
		aliases = aliases,
		nutritionBasis = NutritionBasisInput(BigDecimal("100"), FoodUnit.GRAM),
		nutrients = NutrientValuesInput(
			caloriesKcal = BigDecimal("62"),
			proteinGrams = protein,
			carbohydratesGrams = BigDecimal("4.1"),
			fatGrams = BigDecimal("0.2"),
		),
		defaultServing = DefaultServingInput(BigDecimal("170"), FoodUnit.GRAM),
		source = NutritionSourceInput(NutritionSourceType.PRODUCT_LABEL),
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
