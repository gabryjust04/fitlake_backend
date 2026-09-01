package com.fitlake.food.infrastructure.persistence.repository

import com.fitlake.food.application.UserFoodConflictException
import com.fitlake.food.application.UserFoodPage
import com.fitlake.food.application.UserFoodPageQuery
import com.fitlake.food.application.UserFoodPersistenceException
import com.fitlake.food.application.UserFoodSort
import com.fitlake.food.application.port.UserFoodRepository
import com.fitlake.food.domain.UserFood
import com.fitlake.food.domain.UserFoodAlias
import com.fitlake.food.domain.UserFoodId
import com.fitlake.food.infrastructure.persistence.entity.UserFoodAliasEntity
import com.fitlake.food.infrastructure.persistence.mapper.UserFoodPersistenceMapper
import com.fitlake.user.domain.UserId
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Sort
import org.springframework.stereotype.Repository

@Repository
class JpaUserFoodRepositoryAdapter(
	private val foodRepository: SpringDataUserFoodRepository,
	private val aliasRepository: SpringDataUserFoodAliasRepository,
	private val mapper: UserFoodPersistenceMapper,
) : UserFoodRepository {
	override fun findActiveByIdAndUserId(foodId: UserFoodId, userId: UserId): UserFood? {
		val entity = foodRepository.findByUserFoodIdAndUserIdAndDeletedAtIsNull(foodId.value, userId.value)
			?: return null
		return mapper.toDomain(entity, activeAliases(foodId, userId))
	}

	override fun findActivePage(userId: UserId, query: UserFoodPageQuery): UserFoodPage {
		val page = foodRepository.findAllByUserIdAndDeletedAtIsNull(
			userId.value,
			PageRequest.of(query.page, query.size, query.sort.toSpringSort()),
		)
		val aliases = aliasesByFood(page.content.map { it.userFoodId }, userId)
		return UserFoodPage(
			items = page.content.map { entity -> mapper.toDomain(entity, aliases[entity.userFoodId].orEmpty()) },
			page = query.page,
			size = query.size,
			totalElements = page.totalElements,
		)
	}

	override fun existsActiveBarcode(
		userId: UserId,
		barcode: String,
		excludingFoodId: UserFoodId?,
	): Boolean = if (excludingFoodId == null) {
		foodRepository.existsByUserIdAndBarcodeAndDeletedAtIsNull(userId.value, barcode)
	} else {
		foodRepository.existsByUserIdAndBarcodeAndUserFoodIdNotAndDeletedAtIsNull(
			userId.value,
			barcode,
			excludingFoodId.value,
		)
	}

	override fun findConflictingActiveAlias(
		userId: UserId,
		normalizedAliases: Set<String>,
		excludingFoodId: UserFoodId?,
	): String? {
		if (normalizedAliases.isEmpty()) return null
		return if (excludingFoodId == null) {
			aliasRepository.findFirstActiveConflict(userId.value, normalizedAliases)
		} else {
			aliasRepository.findFirstActiveConflictExcludingFood(
				userId.value,
				normalizedAliases,
				excludingFoodId.value,
			)
		}
	}

	override fun save(food: UserFood): UserFood = try {
		val existing = foodRepository.findByUserFoodIdAndUserId(food.foodId.value, food.userId.value)
		val entity = if (existing == null) {
			foodRepository.saveAndFlush(mapper.toEntity(food))
		} else {
			mapper.updateEntity(food, existing)
			foodRepository.saveAndFlush(existing)
		}
		reconcileAliases(food)
		mapper.toDomain(entity, food.aliases)
	} catch (exception: DataIntegrityViolationException) {
		throw mapConstraintViolation(exception)
	}

	private fun reconcileAliases(food: UserFood) {
		val existing = aliasRepository.findAllByUserFoodIdAndUserId(food.foodId.value, food.userId.value)
		val existingById = existing.associateBy(UserFoodAliasEntity::aliasId)
		val desiredIds = food.aliases.mapTo(hashSetOf(), UserFoodAlias::aliasId)
		val deletionTime = food.deletedAt ?: food.updatedAt

		existing.filter { it.deletedAt == null && it.aliasId !in desiredIds }.forEach { alias ->
			alias.deletedAt = deletionTime
		}
		if (food.deletedAt != null) {
			existing.filter { it.deletedAt == null }.forEach { alias -> alias.deletedAt = food.deletedAt }
		}
		aliasRepository.saveAllAndFlush(existing)

		if (food.deletedAt == null) {
			val activeEntities = food.aliases.map { alias ->
				existingById[alias.aliasId]?.also { entity ->
					entity.alias = alias.value
					entity.normalizedAlias = alias.normalizedValue
					entity.deletedAt = null
				} ?: mapper.toAliasEntity(food, alias)
			}
			aliasRepository.saveAllAndFlush(activeEntities)
		}
	}

	private fun activeAliases(foodId: UserFoodId, userId: UserId): List<UserFoodAlias> =
		aliasRepository.findAllByUserFoodIdAndUserIdAndDeletedAtIsNullOrderByNormalizedAliasAsc(
			foodId.value,
			userId.value,
		)
			.map(mapper::aliasToDomain)

	private fun aliasesByFood(
		foodIds: Collection<java.util.UUID>,
		userId: UserId,
	): Map<java.util.UUID, List<UserFoodAlias>> {
		if (foodIds.isEmpty()) return emptyMap()
		return aliasRepository.findAllByUserFoodIdInAndUserIdAndDeletedAtIsNull(foodIds, userId.value)
			.groupBy(UserFoodAliasEntity::userFoodId)
			.mapValues { (_, entities) -> entities.map(mapper::aliasToDomain).sortedBy(UserFoodAlias::normalizedValue) }
	}

	private fun mapConstraintViolation(exception: DataIntegrityViolationException): RuntimeException {
		val details = generateSequence<Throwable>(exception) { it.cause }
			.mapNotNull(Throwable::message)
			.joinToString(" ")
			.lowercase()
		return when {
			"uq_user_food_active_barcode" in details ->
				UserFoodConflictException("An active food already uses this barcode", exception)
			"uq_user_food_alias_active_normalized" in details ->
				UserFoodConflictException("An active food already uses one of these aliases", exception)
			else -> UserFoodPersistenceException(exception)
		}
	}
}

private fun UserFoodSort.toSpringSort(): Sort = when (this) {
	UserFoodSort.NAME_ASC -> Sort.by(
		Sort.Order.asc("normalizedName"),
		Sort.Order.asc("userFoodId"),
	)
	UserFoodSort.CREATED_AT_DESC -> Sort.by(
		Sort.Order.desc("createdAt"),
		Sort.Order.asc("userFoodId"),
	)
	UserFoodSort.UPDATED_AT_DESC -> Sort.by(
		Sort.Order.desc("updatedAt"),
		Sort.Order.asc("userFoodId"),
	)
}
