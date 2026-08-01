package com.fitlake.daily.application.port

import com.fitlake.user.domain.UserId

sealed interface DailyAiUserFoodMatchResult {
	data class Unique(val food: DailyOwnedUserFood) : DailyAiUserFoodMatchResult

	data object None : DailyAiUserFoodMatchResult

	data object Ambiguous : DailyAiUserFoodMatchResult
}

fun interface DailyAiUserFoodMatchPort {
	fun match(userId: UserId, extractedName: String): DailyAiUserFoodMatchResult
}
