package com.fitlake.food.application

import com.fitlake.food.application.port.UserFoodRepository
import com.fitlake.food.domain.UserFood
import com.fitlake.food.domain.UserFoodDefinition
import com.fitlake.food.domain.UserFoodId
import com.fitlake.shared.application.TransactionExecutor
import com.fitlake.user.domain.UserId
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.time.Clock

@Service
class UserFoodService(
	private val repository: UserFoodRepository,
	private val transactionExecutor: TransactionExecutor,
	private val clock: Clock,
) {
	fun create(userId: UserId, input: UserFoodDefinitionInput): UserFood {
		val definition = validatedDefinition(input)
		return transactionExecutor.required {
			ensureUnique(userId, definition, null)
			repository.save(UserFood.create(userId, definition, clock.instant())).also { food ->
				logger.info("event=user_food_created userId={} foodId={}", userId, food.foodId.value)
			}
		}
	}

	fun get(userId: UserId, foodId: UserFoodId): UserFood = transactionExecutor.required {
		repository.findActiveByIdAndUserId(foodId, userId) ?: throw UserFoodNotFoundException(foodId.value)
	}

	fun list(userId: UserId, query: UserFoodPageQuery): UserFoodPage {
		validatePage(query)
		return transactionExecutor.required { repository.findActivePage(userId, query) }
	}

	fun replace(userId: UserId, foodId: UserFoodId, input: UserFoodDefinitionInput): UserFood {
		val definition = validatedDefinition(input)
		return transactionExecutor.required {
			val current = requireOwned(userId, foodId)
			ensureUnique(userId, definition, foodId)
			val updated = try {
				current.replace(definition, clock.instant())
			} catch (exception: IllegalStateException) {
				throw UserFoodConflictException(exception.message ?: "Food cannot be updated", exception)
			}
			repository.save(updated).also { food ->
				logger.info("event=user_food_updated userId={} foodId={}", userId, food.foodId.value)
			}
		}
	}

	fun softDelete(userId: UserId, foodId: UserFoodId) {
		transactionExecutor.required {
			val current = requireOwned(userId, foodId)
			val deleted = try {
				current.softDelete(clock.instant())
			} catch (exception: IllegalStateException) {
				throw UserFoodNotFoundException(foodId.value)
			}
			repository.save(deleted)
			logger.info("event=user_food_soft_deleted userId={} foodId={}", userId, foodId.value)
			deleted
		}
	}

	private fun requireOwned(userId: UserId, foodId: UserFoodId): UserFood =
		repository.findActiveByIdAndUserId(foodId, userId) ?: throw UserFoodNotFoundException(foodId.value)

	private fun ensureUnique(userId: UserId, definition: UserFoodDefinition, excludingFoodId: UserFoodId?) {
		definition.barcode?.let { barcode ->
			if (repository.existsActiveBarcode(userId, barcode, excludingFoodId)) {
				throw UserFoodConflictException("An active food already uses this barcode")
			}
		}
		val conflict = repository.findConflictingActiveAlias(
			userId = userId,
			normalizedAliases = definition.aliases.mapTo(linkedSetOf()) { it.normalizedValue },
			excludingFoodId = excludingFoodId,
		)
		if (conflict != null) {
			throw UserFoodConflictException("An active food already uses the alias '$conflict'")
		}
	}

	private fun validatedDefinition(input: UserFoodDefinitionInput): UserFoodDefinition = try {
		input.toDefinition()
	} catch (exception: IllegalArgumentException) {
		throw UserFoodValidationException(exception.message ?: "Invalid user food definition")
	}

	private fun validatePage(query: UserFoodPageQuery) {
		if (query.page < 0) throw UserFoodValidationException("Page must not be negative")
		if (query.size !in 1..MAX_PAGE_SIZE) {
			throw UserFoodValidationException("Page size must be between 1 and $MAX_PAGE_SIZE")
		}
	}

	companion object {
		const val MAX_PAGE_SIZE = 100
		private val logger = LoggerFactory.getLogger(UserFoodService::class.java)
	}
}
