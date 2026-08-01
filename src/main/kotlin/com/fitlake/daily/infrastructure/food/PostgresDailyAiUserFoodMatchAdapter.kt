package com.fitlake.daily.infrastructure.food

import com.fitlake.daily.application.port.DailyAiUserFoodMatchPort
import com.fitlake.daily.application.port.DailyAiUserFoodMatchResult
import com.fitlake.food.application.port.UserFoodRepository
import com.fitlake.food.domain.UserFoodId
import com.fitlake.food.domain.UserFoodTextNormalizer
import com.fitlake.shared.application.TransactionExecutor
import com.fitlake.user.domain.UserId
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Component
import java.util.UUID

@Component
class PostgresDailyAiUserFoodMatchAdapter(
	private val jdbcTemplate: NamedParameterJdbcTemplate,
	private val repository: UserFoodRepository,
	private val transactionExecutor: TransactionExecutor,
) : DailyAiUserFoodMatchPort {
	override fun match(userId: UserId, extractedName: String): DailyAiUserFoodMatchResult {
		val normalizedName = UserFoodTextNormalizer.normalize(extractedName)
		if (normalizedName.isEmpty()) return DailyAiUserFoodMatchResult.None

		return transactionExecutor.required {
			val matchingIds = jdbcTemplate.query(
				EXACT_MATCH_SQL,
				MapSqlParameterSource()
					.addValue("userId", userId.value)
					.addValue("normalizedName", normalizedName),
			) { resultSet, _ ->
				requireNotNull(resultSet.getObject("user_food_id", UUID::class.java))
			}
			when (matchingIds.size) {
				0 -> DailyAiUserFoodMatchResult.None
				1 -> repository.findActiveByIdAndUserId(UserFoodId(matchingIds.single()), userId)
					?.takeIf { food ->
						food.normalizedName == normalizedName ||
							food.aliases.any { alias -> alias.normalizedValue == normalizedName }
					}
					?.let { food -> DailyAiUserFoodMatchResult.Unique(food.toDailyOwnedUserFood()) }
					?: DailyAiUserFoodMatchResult.None
				else -> DailyAiUserFoodMatchResult.Ambiguous
			}
		}
	}

	private companion object {
		val EXACT_MATCH_SQL = """
			WITH exact_food_ids AS (
			    SELECT f.user_food_id
			    FROM user_food f
			    WHERE f.user_id = :userId
			      AND f.deleted_at IS NULL
			      AND f.normalized_name = :normalizedName
			    UNION
			    SELECT a.user_food_id
			    FROM user_food_alias a
			    JOIN user_food f
			      ON f.user_food_id = a.user_food_id
			     AND f.user_id = a.user_id
			    WHERE a.user_id = :userId
			      AND a.deleted_at IS NULL
			      AND f.deleted_at IS NULL
			      AND a.normalized_alias = :normalizedName
			)
			SELECT user_food_id
			FROM exact_food_ids
			ORDER BY user_food_id
			LIMIT 2
		""".trimIndent()
	}
}
