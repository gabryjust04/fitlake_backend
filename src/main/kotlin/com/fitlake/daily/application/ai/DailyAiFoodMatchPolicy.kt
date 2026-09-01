package com.fitlake.daily.application.ai

import com.fitlake.daily.application.port.DailyAiFoodCandidate
import com.fitlake.daily.application.port.DailyAiFoodMatchType
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component

sealed interface DailyAiFoodCandidateDecision {
	data class Accept(val candidate: DailyAiFoodCandidate) : DailyAiFoodCandidateDecision
	data object None : DailyAiFoodCandidateDecision
	data class Ambiguous(
		val reason: String,
		val bestMatchedBy: DailyAiFoodMatchType?,
		val bestScore: Double?,
		val runnerUpScore: Double?,
		val candidateCount: Int,
	) : DailyAiFoodCandidateDecision
}

/**
 * Conservative policy shared by every AI catalog lookup. Exact matches win;
 * approximate matches are accepted only with both a strong score and a clear
 * margin over the runner-up. A first search result is never accepted blindly.
 */
@Component
class DailyAiFoodMatchPolicy(
	@Value("\${fitlake.daily.ai.food-match.minimum-score:0.78}")
	private val minimumScore: Double = 0.78,
	@Value("\${fitlake.daily.ai.food-match.minimum-margin:0.12}")
	private val minimumMargin: Double = 0.12,
) {
	init {
		require(minimumScore in 0.0..1.0) { "AI food match minimum score must be between 0 and 1" }
		require(minimumMargin in 0.0..1.0) { "AI food match minimum margin must be between 0 and 1" }
	}

	fun decide(
		candidates: List<DailyAiFoodCandidate>,
		candidateWindowComplete: Boolean = true,
	): DailyAiFoodCandidateDecision {
		if (candidates.isEmpty()) return DailyAiFoodCandidateDecision.None

		val exact = candidates.filter { it.matchedBy in EXACT_TYPES }
		if (exact.size == 1) return DailyAiFoodCandidateDecision.Accept(exact.single())
		if (exact.size > 1) {
			return ambiguous("MULTIPLE_EXACT_MATCHES", exact, candidates.size)
		}

		val approximate = candidates
			.filter { it.matchedBy in APPROXIMATE_TYPES }
			.sortedWith(compareByDescending<DailyAiFoodCandidate> { it.score }.thenBy { it.foodId })
		if (approximate.isEmpty()) return DailyAiFoodCandidateDecision.None
		if (!candidateWindowComplete) {
			return ambiguous("CANDIDATE_WINDOW_TRUNCATED", approximate, candidates.size)
		}

		val best = approximate.first()
		if (best.score < minimumScore) {
			return ambiguous("MATCH_SCORE_BELOW_THRESHOLD", approximate, candidates.size)
		}
		val runnerUp = approximate.getOrNull(1)
		if (runnerUp != null && best.score - runnerUp.score < minimumMargin) {
			return ambiguous("MATCH_MARGIN_TOO_SMALL", approximate, candidates.size)
		}
		return DailyAiFoodCandidateDecision.Accept(best)
	}

	private fun ambiguous(
		reason: String,
		ranked: List<DailyAiFoodCandidate>,
		candidateCount: Int,
	) = DailyAiFoodCandidateDecision.Ambiguous(
		reason = reason,
		bestMatchedBy = ranked.firstOrNull()?.matchedBy,
		bestScore = ranked.firstOrNull()?.score,
		runnerUpScore = ranked.getOrNull(1)?.score,
		candidateCount = candidateCount,
	)

	private companion object {
		val EXACT_TYPES = setOf(
			DailyAiFoodMatchType.EXACT_BARCODE,
			DailyAiFoodMatchType.EXACT_ALIAS,
			DailyAiFoodMatchType.EXACT_NAME,
		)
		val APPROXIMATE_TYPES = setOf(
			DailyAiFoodMatchType.PREFIX_ALIAS,
			DailyAiFoodMatchType.PREFIX_NAME,
			DailyAiFoodMatchType.FUZZY_ALIAS,
			DailyAiFoodMatchType.FUZZY_NAME,
		)
	}
}
