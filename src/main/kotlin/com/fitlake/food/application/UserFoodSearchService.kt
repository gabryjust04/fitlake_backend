package com.fitlake.food.application

import com.fitlake.food.application.port.UserFoodSearchPort
import com.fitlake.shared.application.TransactionExecutor
import com.fitlake.shared.application.elapsedMilliseconds
import com.fitlake.user.domain.UserId
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

@Service
class UserFoodSearchService(
	private val searchPort: UserFoodSearchPort,
	private val transactionExecutor: TransactionExecutor,
) : SearchUserFoodsUseCase {
	override fun search(userId: UserId, query: String, limit: Int): List<UserFoodCandidate> =
		search(userId, query, limit, SearchOrigin.INTERACTIVE)

	override fun searchForDailyAi(userId: UserId, query: String, limit: Int): List<UserFoodCandidate> =
		search(userId, query, limit, SearchOrigin.DAILY_AI)

	private fun search(
		userId: UserId,
		query: String,
		limit: Int,
		origin: SearchOrigin,
	): List<UserFoodCandidate> {
		val startedAtNanos = System.nanoTime()
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
		val results = transactionExecutor.required { searchPort.search(userId, searchQuery) }
		val event = if (origin == SearchOrigin.INTERACTIVE) logger.atInfo() else logger.atDebug()
		event
			.addKeyValue("event", "user_food_search_completed")
			.addKeyValue("outcome", "success")
			.addKeyValue("origin", origin.logValue)
			.addKeyValue("userRef", userId.value)
			.addKeyValue("queryLength", normalized.length)
			.addKeyValue("resultCount", results.size)
			.addKeyValue("durationMs", elapsedMilliseconds(startedAtNanos))
		results.firstOrNull()?.let { top ->
			event
				.addKeyValue("topMatchType", top.matchedBy)
				.addKeyValue("topScoreBucket", scoreBucket(top.score))
		}
		event.log("User food search completed")
		return results
	}

	private enum class SearchOrigin(val logValue: String) {
		INTERACTIVE("interactive"),
		DAILY_AI("daily_ai"),
	}

	private fun scoreBucket(score: Double): String = when {
		score >= 0.95 -> "very_high"
		score >= 0.80 -> "high"
		score >= 0.50 -> "medium"
		else -> "low"
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
