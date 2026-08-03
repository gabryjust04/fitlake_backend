package com.fitlake.food.application

import ch.qos.logback.classic.Level
import com.fitlake.food.domain.FoodUnit
import com.fitlake.food.domain.NutritionSourceType
import com.fitlake.food.domain.UserFoodId
import com.fitlake.shared.application.TransactionExecutor
import com.fitlake.support.ImmediateTransactionExecutor
import com.fitlake.support.InMemoryUserFoodRepository
import com.fitlake.support.LogEventCapture
import com.fitlake.support.renderedLogContent
import com.fitlake.support.structuredFields
import com.fitlake.user.domain.UserId
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class UserFoodServiceTest {
	private val repository = InMemoryUserFoodRepository()
	private val clock = Clock.fixed(Instant.parse("2026-07-31T12:00:00Z"), ZoneOffset.UTC)
	private val service = UserFoodService(repository, ImmediateTransactionExecutor, clock)
	private val search = UserFoodSearchService(repository, ImmediateTransactionExecutor)
	private val userA = UserId(UUID.randomUUID())
	private val userB = UserId(UUID.randomUUID())

	@BeforeEach
	fun reset() = repository.clear()

	@Test
	fun `create generates ids derives owner and owner can retrieve`() {
		val created = LogEventCapture(UserFoodService::class.java).use { capture ->
			val food = service.create(userA, input())
			val event = capture.events.single()
			val fields = event.structuredFields()
			assertEquals(Level.INFO, event.level)
			assertEquals("user_food_created", fields["event"])
			assertEquals("success", fields["outcome"])
			assertEquals(userA.value, fields["userRef"])
			assertEquals(food.foodId.value, fields["userFoodId"])
			assertEquals(NutritionSourceType.PRODUCT_LABEL, fields["sourceType"])
			assertEquals(FoodUnit.GRAM, fields["basisUnit"])
			assertEquals(1, fields["aliasCount"])
			val rendered = event.formattedMessage + fields.entries.joinToString()
			assertFalse(rendered.contains("Greek yogurt"))
			assertFalse(rendered.contains("My Yogurt"))
			assertFalse(rendered.contains("1234567890123"))
			assertFalse(rendered.contains("Label copy"))
			food
		}

		assertEquals(userA, created.userId)
		assertNotEquals(UUID(0, 0), created.foodId.value)
		assertEquals(created, service.get(userA, created.foodId))
		assertFailsWith<UserFoodNotFoundException> { service.get(userB, created.foodId) }
	}

	@Test
	fun `replace recomputes normalized values and replaces aliases`() {
		val created = service.create(userA, input())

		val updated = service.replace(
			userA,
			created.foodId,
			input(name = "Yògurt Proteico", aliases = listOf("post workout")),
		)

		assertEquals("yogurt proteico", updated.normalizedName)
		assertEquals(listOf("post workout"), updated.aliases.map { it.normalizedValue })
		assertTrue(updated.version > created.version)
	}

	@Test
	fun `active aliases and barcodes conflict only within the same user`() {
		service.create(userA, input())
		assertFailsWith<UserFoodConflictException> {
			service.create(userA, input(name = "Other", barcode = "1234567890124"))
		}
		assertFailsWith<UserFoodConflictException> {
			service.create(userA, input(name = "Other", aliases = listOf("other")))
		}

		service.create(userB, input())
		assertEquals(1, service.list(userB, UserFoodPageQuery()).totalElements)
	}

	@Test
	fun `soft delete excludes get list and search and allows alias reuse`() {
		val created = service.create(userA, input())
		service.softDelete(userA, created.foodId)

		assertFailsWith<UserFoodNotFoundException> { service.get(userA, created.foodId) }
		assertEquals(0, service.list(userA, UserFoodPageQuery()).totalElements)
		assertTrue(search.search(userA, "my yogurt").isEmpty())
		service.create(userA, input(name = "Replacement", barcode = "1234567890124"))
	}

	@Test
	fun `foreign update and delete are indistinguishable from missing resources`() {
		val created = service.create(userA, input())

		assertFailsWith<UserFoodNotFoundException> {
			service.replace(userB, created.foodId, input())
		}
		assertFailsWith<UserFoodNotFoundException> { service.softDelete(userB, created.foodId) }
		assertFailsWith<UserFoodNotFoundException> { service.softDelete(userA, UserFoodId(UUID.randomUUID())) }
	}

	@Test
	fun `pagination and search validation reject unsafe bounds`() {
		assertFailsWith<UserFoodValidationException> { service.list(userA, UserFoodPageQuery(page = -1)) }
		assertFailsWith<UserFoodValidationException> { service.list(userA, UserFoodPageQuery(size = 101)) }
		assertFailsWith<UserFoodValidationException> { search.search(userA, " ") }
		assertFailsWith<UserFoodValidationException> { search.search(userA, "a") }
		assertFailsWith<UserFoodValidationException> { search.search(userA, "yogurt", 51) }
	}

	@Test
	fun `replace and delete emit one safe event each`() {
		val created = service.create(userA, input())

		LogEventCapture(UserFoodService::class.java).use { capture ->
			val updated = service.replace(
				userA,
				created.foodId,
				input(name = "Private replacement", aliases = listOf("private alias")),
			)
			service.softDelete(userA, updated.foodId)

			assertEquals(
				listOf("user_food_updated", "user_food_soft_deleted"),
				capture.events.map { it.structuredFields()["event"] },
			)
			capture.events.forEach { event ->
				val fields = event.structuredFields()
				assertEquals("success", fields["outcome"])
				assertEquals(userA.value, fields["userRef"])
				assertEquals(created.foodId.value, fields["userFoodId"])
				val rendered = event.formattedMessage + fields.entries.joinToString()
				assertFalse(rendered.contains("Private replacement"))
				assertFalse(rendered.contains("private alias"))
			}
		}
	}

	@Test
	fun `search logs counts and ranking metadata without query or food content`() {
		service.create(userA, input())
		val query = "my yogurt"

		LogEventCapture(UserFoodSearchService::class.java).use { capture ->
			val results = search.search(userA, query)

			assertEquals(1, results.size)
			val event = capture.events.single()
			val fields = event.structuredFields()
			assertEquals(Level.INFO, event.level)
			assertEquals("user_food_search_completed", fields["event"])
			assertEquals("success", fields["outcome"])
			assertEquals("interactive", fields["origin"])
			assertEquals(userA.value, fields["userRef"])
			assertEquals(query.length, fields["queryLength"])
			assertEquals(1, fields["resultCount"])
			assertEquals(UserFoodMatchType.EXACT_ALIAS, fields["topMatchType"])
			assertEquals("very_high", fields["topScoreBucket"])
			val rendered = event.formattedMessage + fields.entries.joinToString()
			assertFalse(rendered.contains(query))
			assertFalse(rendered.contains("Greek yogurt"))
			assertFalse(rendered.contains("1234567890123"))
		}
	}

	@Test
	fun `daily ai catalog search logs the same safe metadata only at debug`() {
		service.create(userA, input())
		val query = "my yogurt"

		LogEventCapture(UserFoodSearchService::class.java, Level.DEBUG).use { capture ->
			val results = search.searchForDailyAi(userA, query, 50)

			assertEquals(1, results.size)
			val event = capture.events.single()
			val fields = event.structuredFields()
			assertEquals(Level.DEBUG, event.level)
			assertEquals("user_food_search_completed", fields["event"])
			assertEquals("success", fields["outcome"])
			assertEquals("daily_ai", fields["origin"])
			assertEquals(userA.value, fields["userRef"])
			assertEquals(query.length, fields["queryLength"])
			assertEquals(1, fields["resultCount"])
			assertEquals(UserFoodMatchType.EXACT_ALIAS, fields["topMatchType"])
			assertEquals("very_high", fields["topScoreBucket"])
			assertTrue((fields["durationMs"] as Number).toLong() >= 0L)

			val rendered = capture.events.renderedLogContent()
			assertFalse(rendered.contains(query))
			assertFalse(rendered.contains("Greek yogurt"))
			assertFalse(rendered.contains("1234567890123"))
		}
	}

	@Test
	fun `failed transaction does not emit a catalog success event`() {
		val failingCommit = object : TransactionExecutor {
			override fun <T : Any> required(action: () -> T): T {
				action()
				throw IllegalStateException("commit failed after persisting Greek yogurt")
			}
		}
		val failingService = UserFoodService(repository, failingCommit, clock)

		LogEventCapture(UserFoodService::class.java).use { capture ->
			assertFailsWith<IllegalStateException> { failingService.create(userA, input()) }
			assertTrue(capture.events.isEmpty())
		}
	}

	private fun input(
		name: String = "Greek yogurt",
		barcode: String = "1234567890123",
		aliases: List<String> = listOf("My Yogurt"),
	) = UserFoodDefinitionInput(
		name = name,
		brand = "Brand",
		barcode = barcode,
		description = "Label copy",
		aliases = aliases,
		nutritionBasis = NutritionBasisInput(BigDecimal("100"), FoodUnit.GRAM),
		nutrients = NutrientValuesInput(
			caloriesKcal = BigDecimal("62"),
			proteinGrams = BigDecimal("9.5"),
			carbohydratesGrams = BigDecimal("4.1"),
			fatGrams = BigDecimal("0.2"),
		),
		defaultServing = DefaultServingInput(BigDecimal("170"), FoodUnit.GRAM),
		source = NutritionSourceInput(NutritionSourceType.PRODUCT_LABEL),
	)
}
