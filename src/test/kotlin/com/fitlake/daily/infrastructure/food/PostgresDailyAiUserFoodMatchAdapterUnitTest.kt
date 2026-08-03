package com.fitlake.daily.infrastructure.food

import com.fitlake.daily.application.ai.DailyAiFoodMatchPolicy
import com.fitlake.daily.application.port.DailyAiUserFoodMatchResult
import com.fitlake.daily.application.port.DailyOwnedUserFood
import com.fitlake.daily.application.port.DailyUserFoodLookupPort
import com.fitlake.food.application.SearchUserFoodsUseCase
import com.fitlake.food.application.UserFoodCandidate
import com.fitlake.food.application.UserFoodMatchType
import com.fitlake.food.domain.FoodUnit
import com.fitlake.food.domain.NutritionBasis
import com.fitlake.food.domain.NutritionSourceType
import com.fitlake.food.domain.UserFoodId
import com.fitlake.user.domain.UserId
import java.math.BigDecimal
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class PostgresDailyAiUserFoodMatchAdapterUnitTest {
	@Test
	fun `full AI candidate window stays ambiguous and never loads a truncated approximate winner`() {
		var requestedLimit: Int? = null
		var lookupCount = 0
		val search = object : SearchUserFoodsUseCase {
			override fun search(userId: UserId, query: String, limit: Int): List<UserFoodCandidate> {
				requestedLimit = limit
				return (1..limit).map { index -> candidate(index, 0.99 - index / 10_000.0) }
			}
		}
		val lookup = object : DailyUserFoodLookupPort {
			override fun findActiveOwnedFood(userId: UserId, userFoodId: UUID): DailyOwnedUserFood? {
				lookupCount++
				return null
			}
		}
		val adapter = PostgresDailyAiUserFoodMatchAdapter(
			searchUserFoods = search,
			lookupPort = lookup,
			matchPolicy = DailyAiFoodMatchPolicy(minimumScore = 0.78, minimumMargin = 0.12),
		)

		val result = assertIs<DailyAiUserFoodMatchResult.Ambiguous>(
			adapter.match(UserId(UUID.randomUUID()), "similar food"),
		)

		assertEquals(50, requestedLimit)
		assertEquals("CANDIDATE_WINDOW_TRUNCATED", result.reason)
		assertEquals(0, lookupCount)
	}

	private fun candidate(index: Int, score: Double) = UserFoodCandidate(
		foodId = UserFoodId(UUID(0, index.toLong())),
		name = "Food $index",
		brand = null,
		matchedBy = UserFoodMatchType.FUZZY_NAME,
		matchedText = "food $index",
		score = score,
		nutritionBasis = NutritionBasis(BigDecimal("100"), FoodUnit.GRAM),
		defaultServing = null,
		sourceType = NutritionSourceType.USER_ENTERED,
	)
}
