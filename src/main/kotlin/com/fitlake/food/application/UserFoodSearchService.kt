package com.fitlake.food.application

import com.fitlake.food.application.port.UserFoodSearchPort
import com.fitlake.shared.application.TransactionExecutor
import com.fitlake.user.domain.UserId
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import kotlin.system.measureNanoTime

@Service
class UserFoodSearchService(
	private val searchPort: UserFoodSearchPort,
	private val transactionExecutor: TransactionExecutor,
) : SearchUserFoodsUseCase {
	override fun search(userId: UserId, query: String, limit: Int): List<UserFoodCandidate> {
		val normalized = normalizeSearchQuery(query)
		if (normalized.length < MIN_QUERY_LENGTH) {
			throw UserFoodValidationException("Search query must contain at least $MIN_QUERY_LENGTH searchable characters")
		}
		if (normalized.length > MAX_QUERY_LENGTH) {
			throw UserFoodValidationException("Search query must not exceed $MAX_QUERY_LENGTH characters")
		}
		if (limit !in 1..MAX_SEARCH_LIMIT) {
			throw UserFoodValidationException("Search limit must be between 1 and $MAX_SEARCH_LIMIT")
		}
		val searchQuery = UserFoodSearchQuery(
			normalizedQuery = normalized,
			barcode = query.trim().takeIf { BARCODE_PATTERN.matches(it) },
			limit = limit,
			fuzzyEnabled = normalized.length >= MIN_FUZZY_QUERY_LENGTH,
		)
		lateinit var results: List<UserFoodCandidate>
		val durationNanos = measureNanoTime {
			results = transactionExecutor.required { searchPort.search(userId, searchQuery) }
		}
		logger.info(
			"event=user_food_search_completed userId={} resultCount={} durationMs={}",
			userId,
			results.size,
			durationNanos / 1_000_000,
		)
		return results
	}

	companion object {
		const val MIN_QUERY_LENGTH = 2
		const val MIN_FUZZY_QUERY_LENGTH = 3
		const val MAX_QUERY_LENGTH = 200
		const val MAX_SEARCH_LIMIT = 50
		private val BARCODE_PATTERN = Regex("^[0-9]{8,14}$")
		private val logger = LoggerFactory.getLogger(UserFoodSearchService::class.java)
	}
}
