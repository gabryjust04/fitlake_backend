package com.fitlake.support

import com.fitlake.food.application.UserFoodCandidate
import com.fitlake.food.application.UserFoodConflictException
import com.fitlake.food.application.UserFoodMatchType
import com.fitlake.food.application.UserFoodPage
import com.fitlake.food.application.UserFoodPageQuery
import com.fitlake.food.application.UserFoodSearchQuery
import com.fitlake.food.application.UserFoodSort
import com.fitlake.food.application.port.UserFoodRepository
import com.fitlake.food.application.port.UserFoodSearchPort
import com.fitlake.food.domain.UserFood
import com.fitlake.food.domain.UserFoodId
import com.fitlake.user.domain.UserId
import java.util.concurrent.ConcurrentHashMap

class InMemoryUserFoodRepository : UserFoodRepository, UserFoodSearchPort {
	private val foods = ConcurrentHashMap<UserFoodId, UserFood>()

	override fun findActiveByIdAndUserId(foodId: UserFoodId, userId: UserId): UserFood? =
		foods[foodId]?.takeIf { it.userId == userId && it.active }

	override fun findActivePage(userId: UserId, query: UserFoodPageQuery): UserFoodPage {
		val comparator = when (query.sort) {
			UserFoodSort.NAME_ASC -> compareBy<UserFood> { it.normalizedName }.thenBy { it.foodId.value }
			UserFoodSort.CREATED_AT_DESC -> compareByDescending<UserFood> { it.createdAt }.thenBy { it.foodId.value }
			UserFoodSort.UPDATED_AT_DESC -> compareByDescending<UserFood> { it.updatedAt }.thenBy { it.foodId.value }
		}
		val matching = foods.values.filter { it.userId == userId && it.active }.sortedWith(comparator)
		return UserFoodPage(
			items = matching.drop(query.page * query.size).take(query.size),
			page = query.page,
			size = query.size,
			totalElements = matching.size.toLong(),
		)
	}

	override fun existsActiveBarcode(
		userId: UserId,
		barcode: String,
		excludingFoodId: UserFoodId?,
	): Boolean = foods.values.any {
		it.userId == userId && it.active && it.barcode == barcode && it.foodId != excludingFoodId
	}

	override fun findConflictingActiveAlias(
		userId: UserId,
		normalizedAliases: Set<String>,
		excludingFoodId: UserFoodId?,
	): String? = foods.values
		.asSequence()
		.filter { it.userId == userId && it.active && it.foodId != excludingFoodId }
		.flatMap { it.aliases.asSequence() }
		.map { it.normalizedValue }
		.filter { it in normalizedAliases }
		.sorted()
		.firstOrNull()

	@Synchronized
	override fun save(food: UserFood): UserFood {
		if (existsActiveBarcode(food.userId, food.barcode ?: "", food.foodId) && food.barcode != null) {
			throw UserFoodConflictException("duplicate barcode")
		}
		findConflictingActiveAlias(
			food.userId,
			food.aliases.mapTo(hashSetOf()) { it.normalizedValue },
			food.foodId,
		)?.let { throw UserFoodConflictException("duplicate alias") }
		val existing = foods[food.foodId]
		val saved = if (existing == null) food else food.copy(version = existing.version + 1)
		foods[saved.foodId] = saved
		return saved
	}

	override fun search(userId: UserId, query: UserFoodSearchQuery): List<UserFoodCandidate> = foods.values
		.asSequence()
		.filter { it.userId == userId && it.active }
		.mapNotNull { food -> bestMatch(food, query) }
		.sortedWith(
			compareBy<Pair<UserFood, Match>> { it.second.type.ordinal }
				.thenByDescending { it.second.score }
				.thenBy { it.first.normalizedName }
				.thenBy { it.first.foodId.value },
		)
		.take(query.limit)
		.map { (food, match) ->
			UserFoodCandidate(
				foodId = food.foodId,
				name = food.name,
				brand = food.brand,
				matchedBy = match.type,
				matchedText = match.text,
				score = match.score,
				nutritionBasis = food.nutritionBasis,
				defaultServing = food.defaultServing,
				sourceType = food.source.type,
			)
		}.toList()

	fun all(): List<UserFood> = foods.values.toList()
	fun clear() = foods.clear()

	private fun bestMatch(food: UserFood, query: UserFoodSearchQuery): Pair<UserFood, Match>? {
		val matches = buildList {
			if (query.barcode != null && food.barcode == query.barcode) {
				add(Match(UserFoodMatchType.EXACT_BARCODE, food.barcode, 1.0))
			}
			food.aliases.forEach { alias ->
				when {
					alias.normalizedValue == query.normalizedQuery ->
						add(Match(UserFoodMatchType.EXACT_ALIAS, alias.value, 1.0))
					alias.normalizedValue.startsWith(query.normalizedQuery) ->
						add(Match(UserFoodMatchType.PREFIX_ALIAS, alias.value, prefixScore(query.normalizedQuery, alias.normalizedValue)))
					query.fuzzyEnabled -> trigramSimilarity(alias.normalizedValue, query.normalizedQuery)
						.takeIf { it >= 0.3 }
						?.let { add(Match(UserFoodMatchType.FUZZY_ALIAS, alias.value, it)) }
				}
			}
			when {
				food.normalizedName == query.normalizedQuery ->
					add(Match(UserFoodMatchType.EXACT_NAME, food.name, 1.0))
				food.normalizedName.startsWith(query.normalizedQuery) ->
					add(Match(UserFoodMatchType.PREFIX_NAME, food.name, prefixScore(query.normalizedQuery, food.normalizedName)))
				query.fuzzyEnabled -> trigramSimilarity(food.normalizedName, query.normalizedQuery)
					.takeIf { it >= 0.3 }
					?.let { add(Match(UserFoodMatchType.FUZZY_NAME, food.name, it)) }
			}
		}
		return matches.minWithOrNull(compareBy<Match> { it.type.ordinal }.thenByDescending { it.score })
			?.let { food to it }
	}

	private fun prefixScore(query: String, value: String) = (query.length.toDouble() / value.length).coerceAtMost(0.999)

	private fun trigramSimilarity(left: String, right: String): Double {
		val leftTrigrams = "  $left ".windowed(3).toSet()
		val rightTrigrams = "  $right ".windowed(3).toSet()
		if (leftTrigrams.isEmpty() && rightTrigrams.isEmpty()) return 1.0
		return (2.0 * leftTrigrams.intersect(rightTrigrams).size) / (leftTrigrams.size + rightTrigrams.size)
	}

	private data class Match(val type: UserFoodMatchType, val text: String, val score: Double)
}
