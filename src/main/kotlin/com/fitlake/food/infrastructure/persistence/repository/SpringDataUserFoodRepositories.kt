package com.fitlake.food.infrastructure.persistence.repository

import com.fitlake.food.infrastructure.persistence.entity.UserFoodAliasEntity
import com.fitlake.food.infrastructure.persistence.entity.UserFoodEntity
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.util.UUID

interface SpringDataUserFoodRepository : JpaRepository<UserFoodEntity, UUID> {
	fun findByUserFoodIdAndUserId(userFoodId: UUID, userId: UUID): UserFoodEntity?

	fun findByUserFoodIdAndUserIdAndDeletedAtIsNull(userFoodId: UUID, userId: UUID): UserFoodEntity?

	fun findAllByUserIdAndDeletedAtIsNull(userId: UUID, pageable: Pageable): Page<UserFoodEntity>

	fun existsByUserIdAndBarcodeAndDeletedAtIsNull(userId: UUID, barcode: String): Boolean

	fun existsByUserIdAndBarcodeAndUserFoodIdNotAndDeletedAtIsNull(
		userId: UUID,
		barcode: String,
		userFoodId: UUID,
	): Boolean
}

interface SpringDataUserFoodAliasRepository : JpaRepository<UserFoodAliasEntity, UUID> {
	fun findAllByUserFoodIdAndUserIdAndDeletedAtIsNullOrderByNormalizedAliasAsc(
		userFoodId: UUID,
		userId: UUID,
	): List<UserFoodAliasEntity>

	fun findAllByUserFoodIdInAndUserIdAndDeletedAtIsNull(
		userFoodIds: Collection<UUID>,
		userId: UUID,
	): List<UserFoodAliasEntity>

	fun findAllByUserFoodIdAndUserId(userFoodId: UUID, userId: UUID): List<UserFoodAliasEntity>

	@Query(
		value = """
			SELECT normalized_alias
			FROM user_food_alias
			WHERE user_id = :userId
			  AND normalized_alias IN (:aliases)
			  AND deleted_at IS NULL
			ORDER BY normalized_alias
			LIMIT 1
		""",
		nativeQuery = true,
	)
	fun findFirstActiveConflict(
		@Param("userId") userId: UUID,
		@Param("aliases") aliases: Collection<String>,
	): String?

	@Query(
		value = """
			SELECT normalized_alias
			FROM user_food_alias
			WHERE user_id = :userId
			  AND normalized_alias IN (:aliases)
			  AND user_food_id <> :excludedFoodId
			  AND deleted_at IS NULL
			ORDER BY normalized_alias
			LIMIT 1
		""",
		nativeQuery = true,
	)
	fun findFirstActiveConflictExcludingFood(
		@Param("userId") userId: UUID,
		@Param("aliases") aliases: Collection<String>,
		@Param("excludedFoodId") excludedFoodId: UUID,
	): String?
}
