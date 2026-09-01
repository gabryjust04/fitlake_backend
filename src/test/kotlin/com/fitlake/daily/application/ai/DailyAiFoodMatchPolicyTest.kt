package com.fitlake.daily.application.ai

import com.fitlake.daily.application.port.DailyAiFoodCandidate
import com.fitlake.daily.application.port.DailyAiFoodMatchType
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class DailyAiFoodMatchPolicyTest {
	private val policy = DailyAiFoodMatchPolicy(minimumScore = 0.78, minimumMargin = 0.12)

	@Test
	fun `one exact match is accepted before approximate candidates`() {
		val exact = candidate(DailyAiFoodMatchType.EXACT_ALIAS, 1.0)
		val fuzzy = candidate(DailyAiFoodMatchType.FUZZY_NAME, 0.99)

		val decision = assertIs<DailyAiFoodCandidateDecision.Accept>(policy.decide(listOf(fuzzy, exact)))

		assertEquals(exact.foodId, decision.candidate.foodId)
	}

	@Test
	fun `multiple exact foods are ambiguous`() {
		assertIs<DailyAiFoodCandidateDecision.Ambiguous>(
			policy.decide(
				listOf(
					candidate(DailyAiFoodMatchType.EXACT_NAME, 1.0),
					candidate(DailyAiFoodMatchType.EXACT_ALIAS, 1.0),
				),
			),
		)
	}

	@Test
	fun `strong fuzzy match with a clear margin is accepted`() {
		val best = candidate(DailyAiFoodMatchType.FUZZY_ALIAS, 0.91)
		val runnerUp = candidate(DailyAiFoodMatchType.FUZZY_NAME, 0.72)

		val decision = assertIs<DailyAiFoodCandidateDecision.Accept>(policy.decide(listOf(runnerUp, best)))

		assertEquals(best.foodId, decision.candidate.foodId)
	}

	@Test
	fun `weak or close approximate matches stay ambiguous`() {
		val weak = assertIs<DailyAiFoodCandidateDecision.Ambiguous>(
			policy.decide(listOf(candidate(DailyAiFoodMatchType.FUZZY_NAME, 0.77))),
		)
		assertEquals("MATCH_SCORE_BELOW_THRESHOLD", weak.reason)

		val close = assertIs<DailyAiFoodCandidateDecision.Ambiguous>(
			policy.decide(
				listOf(
					candidate(DailyAiFoodMatchType.PREFIX_NAME, 0.90),
					candidate(DailyAiFoodMatchType.FUZZY_ALIAS, 0.82),
				),
			),
		)
		assertEquals("MATCH_MARGIN_TOO_SMALL", close.reason)
	}

	@Test
	fun `approximate match is never accepted from a potentially truncated candidate window`() {
		val decision = assertIs<DailyAiFoodCandidateDecision.Ambiguous>(
			policy.decide(
				candidates = listOf(candidate(DailyAiFoodMatchType.FUZZY_NAME, 0.99)),
				candidateWindowComplete = false,
			),
		)

		assertEquals("CANDIDATE_WINDOW_TRUNCATED", decision.reason)
	}

	private fun candidate(type: DailyAiFoodMatchType, score: Double) = DailyAiFoodCandidate(
		foodId = UUID.randomUUID(),
		matchedBy = type,
		score = score,
	)
}
