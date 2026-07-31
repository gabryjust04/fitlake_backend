package com.fitlake.food.infrastructure.persistence.repository

import com.fitlake.food.application.UserFoodCandidate
import com.fitlake.food.application.UserFoodMatchType
import com.fitlake.food.application.UserFoodSearchQuery
import com.fitlake.food.application.port.UserFoodSearchPort
import com.fitlake.food.domain.DefaultServing
import com.fitlake.food.domain.FoodUnit
import com.fitlake.food.domain.NutritionBasis
import com.fitlake.food.domain.NutritionSourceType
import com.fitlake.food.domain.UserFoodId
import com.fitlake.user.domain.UserId
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import java.math.BigDecimal
import java.sql.ResultSet
import java.util.UUID

@Repository
class PostgresUserFoodSearchAdapter(
	private val jdbcTemplate: NamedParameterJdbcTemplate,
) : UserFoodSearchPort {
	override fun search(userId: UserId, query: UserFoodSearchQuery): List<UserFoodCandidate> {
		jdbcTemplate.jdbcTemplate.queryForObject(
			"SELECT set_config('pg_trgm.similarity_threshold', '0.30', true)",
			String::class.java,
		)
		val parameters = MapSqlParameterSource()
			.addValue("userId", userId.value)
			.addValue("query", query.normalizedQuery)
			.addValue("prefix", escapeLike(query.normalizedQuery) + "%")
			.addValue("barcode", query.barcode)
			.addValue("fuzzyEnabled", query.fuzzyEnabled)
			.addValue("limit", query.limit)
		return jdbcTemplate.query(SEARCH_SQL, parameters) { resultSet, _ -> resultSet.toCandidate() }
	}

	private fun ResultSet.toCandidate(): UserFoodCandidate {
		val defaultServingAmount = getBigDecimal("default_serving_amount")
		return UserFoodCandidate(
			foodId = UserFoodId(getObject("user_food_id", UUID::class.java)),
			name = getString("name"),
			brand = getString("brand"),
			matchedBy = UserFoodMatchType.valueOf(getString("matched_by")),
			matchedText = getString("matched_text"),
			score = getDouble("score"),
			nutritionBasis = NutritionBasis(
				getBigDecimal("basis_amount").normalizedDecimal(),
				FoodUnit.valueOf(getString("basis_unit")),
			),
			defaultServing = defaultServingAmount?.let { amount ->
				DefaultServing(
					amount.normalizedDecimal(),
					FoodUnit.valueOf(getString("default_serving_unit")),
				)
			},
			sourceType = NutritionSourceType.valueOf(getString("source_type")),
		)
	}

	private fun escapeLike(value: String): String = value
		.replace("\\", "\\\\")
		.replace("%", "\\%")
		.replace("_", "\\_")

	companion object {
		private val SEARCH_SQL = """
			WITH candidates AS (
			    SELECT f.user_food_id, 1 AS match_rank, 'EXACT_BARCODE' AS matched_by,
			           f.barcode AS matched_text, CAST(1.0 AS DOUBLE PRECISION) AS score
			    FROM user_food f
			    WHERE f.user_id = :userId
			      AND f.deleted_at IS NULL
			      AND f.barcode = CAST(:barcode AS VARCHAR)
			    UNION ALL
			    SELECT f.user_food_id, 2, 'EXACT_ALIAS', a.alias, CAST(1.0 AS DOUBLE PRECISION)
			    FROM user_food_alias a
			    JOIN user_food f ON f.user_food_id = a.user_food_id AND f.user_id = a.user_id
			    WHERE a.user_id = :userId
			      AND a.deleted_at IS NULL
			      AND f.deleted_at IS NULL
			      AND a.normalized_alias = :query
			    UNION ALL
			    SELECT f.user_food_id, 3, 'EXACT_NAME', f.name, CAST(1.0 AS DOUBLE PRECISION)
			    FROM user_food f
			    WHERE f.user_id = :userId
			      AND f.deleted_at IS NULL
			      AND f.normalized_name = :query
			    UNION ALL
			    SELECT f.user_food_id, 4, 'PREFIX_ALIAS', a.alias,
			           LEAST(0.999, CAST(LENGTH(CAST(:query AS TEXT)) AS DOUBLE PRECISION) / NULLIF(LENGTH(a.normalized_alias), 0))
			    FROM user_food_alias a
			    JOIN user_food f ON f.user_food_id = a.user_food_id AND f.user_id = a.user_id
			    WHERE a.user_id = :userId
			      AND a.deleted_at IS NULL
			      AND f.deleted_at IS NULL
			      AND a.normalized_alias LIKE :prefix ESCAPE E'\\'
			    UNION ALL
			    SELECT f.user_food_id, 5, 'PREFIX_NAME', f.name,
			           LEAST(0.999, CAST(LENGTH(CAST(:query AS TEXT)) AS DOUBLE PRECISION) / NULLIF(LENGTH(f.normalized_name), 0))
			    FROM user_food f
			    WHERE f.user_id = :userId
			      AND f.deleted_at IS NULL
			      AND f.normalized_name LIKE :prefix ESCAPE E'\\'
			    UNION ALL
			    SELECT f.user_food_id, 6, 'FUZZY_ALIAS', a.alias, similarity(a.normalized_alias, CAST(:query AS TEXT))
			    FROM user_food_alias a
			    JOIN user_food f ON f.user_food_id = a.user_food_id AND f.user_id = a.user_id
			    WHERE :fuzzyEnabled
			      AND a.user_id = :userId
			      AND a.deleted_at IS NULL
			      AND f.deleted_at IS NULL
			      AND a.normalized_alias % CAST(:query AS TEXT)
			      AND similarity(a.normalized_alias, CAST(:query AS TEXT)) >= 0.30
			    UNION ALL
			    SELECT f.user_food_id, 7, 'FUZZY_NAME', f.name, similarity(f.normalized_name, CAST(:query AS TEXT))
			    FROM user_food f
			    WHERE :fuzzyEnabled
			      AND f.user_id = :userId
			      AND f.deleted_at IS NULL
			      AND f.normalized_name % CAST(:query AS TEXT)
			      AND similarity(f.normalized_name, CAST(:query AS TEXT)) >= 0.30
			), ranked AS (
			    SELECT candidates.*,
			           ROW_NUMBER() OVER (
			               PARTITION BY user_food_id
			               ORDER BY match_rank, score DESC, matched_text, user_food_id
			           ) AS row_number
			    FROM candidates
			)
			SELECT f.user_food_id, f.name, f.brand, f.basis_amount, f.basis_unit,
			       f.default_serving_amount, f.default_serving_unit, f.source_type,
			       ranked.matched_by, ranked.matched_text, ranked.score
			FROM ranked
			JOIN user_food f ON f.user_food_id = ranked.user_food_id
			                AND f.user_id = :userId
			                AND f.deleted_at IS NULL
			WHERE ranked.row_number = 1
			ORDER BY ranked.match_rank, ranked.score DESC, f.normalized_name, f.user_food_id
			LIMIT :limit
		""".trimIndent()
	}
}

private fun BigDecimal.normalizedDecimal(): BigDecimal = stripTrailingZeros().let { normalized ->
	if (normalized.scale() < 0) normalized.setScale(0) else normalized
}
