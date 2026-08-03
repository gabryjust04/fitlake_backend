package com.fitlake.daily.infrastructure.food

import com.fitlake.daily.application.ai.DailyAiFoodCandidateDecision
import com.fitlake.daily.application.ai.DailyAiFoodMatchPolicy
import com.fitlake.daily.application.port.DailyAiFoodCandidate
import com.fitlake.daily.application.port.DailyAiFoodMatchType
import com.fitlake.daily.application.port.DailyAiUserFoodMatchPort
import com.fitlake.daily.application.port.DailyAiUserFoodMatchResult
import com.fitlake.daily.application.port.DailyUserFoodLookupPort
import com.fitlake.food.application.SearchUserFoodsUseCase
import com.fitlake.food.application.UserFoodCandidate
import com.fitlake.food.application.UserFoodMatchType
import com.fitlake.food.application.UserFoodValidationException
import com.fitlake.user.domain.UserId
import org.springframework.stereotype.Component

/** Reuses the same ranked PostgreSQL search used by the UI, then applies a stricter AI policy. */
@Component
class PostgresDailyAiUserFoodMatchAdapter(
	private val searchUserFoods: SearchUserFoodsUseCase,
	private val lookupPort: DailyUserFoodLookupPort,
	private val matchPolicy: DailyAiFoodMatchPolicy,
) : DailyAiUserFoodMatchPort {
	override fun match(userId: UserId, searchText: String): DailyAiUserFoodMatchResult {
		val catalogCandidates = try {
			searchUserFoods.searchForDailyAi(userId, searchText, SEARCH_LIMIT)
		} catch (_: UserFoodValidationException) {
			return DailyAiUserFoodMatchResult.None
		}
		val candidates = catalogCandidates.map { it.toDailyCandidate() }
		return when (
			val decision = matchPolicy.decide(
				candidates = candidates,
				candidateWindowComplete = catalogCandidates.size < SEARCH_LIMIT,
			)
		) {
			DailyAiFoodCandidateDecision.None -> DailyAiUserFoodMatchResult.None
			is DailyAiFoodCandidateDecision.Ambiguous -> DailyAiUserFoodMatchResult.Ambiguous(
				reason = decision.reason,
				bestMatchedBy = decision.bestMatchedBy,
				bestScore = decision.bestScore,
				runnerUpScore = decision.runnerUpScore,
				candidateCount = decision.candidateCount,
			)
			is DailyAiFoodCandidateDecision.Accept -> {
				val candidate = decision.candidate
				val food = lookupPort.findActiveOwnedFood(userId, candidate.foodId)
					?: return DailyAiUserFoodMatchResult.None
				DailyAiUserFoodMatchResult.Unique(food, candidate.matchedBy, candidate.score)
			}
		}
	}

	private fun UserFoodCandidate.toDailyCandidate() = DailyAiFoodCandidate(
		foodId = foodId.value,
		matchedBy = matchedBy.toDailyMatchType(),
		score = score,
	)

	private fun UserFoodMatchType.toDailyMatchType(): DailyAiFoodMatchType = when (this) {
		UserFoodMatchType.EXACT_BARCODE -> DailyAiFoodMatchType.EXACT_BARCODE
		UserFoodMatchType.EXACT_ALIAS -> DailyAiFoodMatchType.EXACT_ALIAS
		UserFoodMatchType.EXACT_NAME -> DailyAiFoodMatchType.EXACT_NAME
		UserFoodMatchType.PREFIX_ALIAS -> DailyAiFoodMatchType.PREFIX_ALIAS
		UserFoodMatchType.PREFIX_NAME -> DailyAiFoodMatchType.PREFIX_NAME
		UserFoodMatchType.FUZZY_ALIAS -> DailyAiFoodMatchType.FUZZY_ALIAS
		UserFoodMatchType.FUZZY_NAME -> DailyAiFoodMatchType.FUZZY_NAME
	}

	private companion object {
		const val SEARCH_LIMIT = 50
	}
}
