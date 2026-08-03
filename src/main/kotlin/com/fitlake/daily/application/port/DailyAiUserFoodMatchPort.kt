package com.fitlake.daily.application.port

import com.fitlake.user.domain.UserId
import java.util.UUID

/** Daily-owned projection of the catalog search result. */
data class DailyAiFoodCandidate(
	val foodId: UUID,
	val matchedBy: DailyAiFoodMatchType,
	val score: Double,
)

enum class DailyAiFoodMatchType {
	EXACT_BARCODE,
	EXACT_ALIAS,
	EXACT_NAME,
	PREFIX_ALIAS,
	PREFIX_NAME,
	FUZZY_ALIAS,
	FUZZY_NAME,
}

sealed interface DailyAiUserFoodMatchResult {
	data class Unique(
		val food: DailyOwnedUserFood,
		val matchedBy: DailyAiFoodMatchType = DailyAiFoodMatchType.EXACT_NAME,
		val score: Double = 1.0,
	) : DailyAiUserFoodMatchResult

	data object None : DailyAiUserFoodMatchResult

	data class Ambiguous(
		val reason: String = "AMBIGUOUS_MATCH",
		val bestMatchedBy: DailyAiFoodMatchType? = null,
		val bestScore: Double? = null,
		val runnerUpScore: Double? = null,
		val candidateCount: Int? = null,
	) : DailyAiUserFoodMatchResult
}

fun interface DailyAiUserFoodMatchPort {
	fun match(userId: UserId, searchText: String): DailyAiUserFoodMatchResult
}
