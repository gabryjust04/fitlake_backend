package com.fitlake.food.application

import com.fitlake.food.domain.FoodUnit
import com.fitlake.food.domain.NutritionSourceType
import com.fitlake.food.domain.UserFoodId
import com.fitlake.support.ImmediateTransactionExecutor
import com.fitlake.support.InMemoryUserFoodRepository
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
		val created = service.create(userA, input())

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
