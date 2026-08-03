package com.fitlake.food.application

import com.fitlake.food.application.port.UserFoodRepository
import com.fitlake.food.domain.UserFood
import com.fitlake.food.domain.UserFoodDefinition
import com.fitlake.food.domain.UserFoodId
import com.fitlake.shared.application.TransactionExecutor
import com.fitlake.shared.application.elapsedMilliseconds
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
		val startedAtNanos = System.nanoTime()
		val definition = validatedDefinition(input)
		val created = transactionExecutor.required {
			ensureUnique(userId, definition, null)
			repository.save(UserFood.create(userId, definition, clock.instant()))
		}
		logChange("user_food_created", "User food created", created, startedAtNanos)
		return created
	}

	fun get(userId: UserId, foodId: UserFoodId): UserFood = transactionExecutor.required {
		repository.findActiveByIdAndUserId(foodId, userId) ?: throw UserFoodNotFoundException(foodId.value)
	}

	fun list(userId: UserId, query: UserFoodPageQuery): UserFoodPage {
		validatePage(query)
		return transactionExecutor.required { repository.findActivePage(userId, query) }
	}

	fun replace(userId: UserId, foodId: UserFoodId, input: UserFoodDefinitionInput): UserFood {
		val startedAtNanos = System.nanoTime()
		val definition = validatedDefinition(input)
		val replaced = transactionExecutor.required {
			val current = requireOwned(userId, foodId)
			ensureUnique(userId, definition, foodId)
			val updated = try {
				current.replace(definition, clock.instant())
			} catch (exception: IllegalStateException) {
				throw UserFoodConflictException(exception.message ?: "Food cannot be updated", exception)
			}
			repository.save(updated)
		}
		logChange("user_food_updated", "User food updated", replaced, startedAtNanos)
		return replaced
	}

	fun softDelete(userId: UserId, foodId: UserFoodId) {
		val startedAtNanos = System.nanoTime()
		val deleted = transactionExecutor.required {
			val current = requireOwned(userId, foodId)
			val deleted = try {
				current.softDelete(clock.instant())
			} catch (exception: IllegalStateException) {
				throw UserFoodNotFoundException(foodId.value)
			}
			repository.save(deleted)
			deleted
		}
		logChange("user_food_soft_deleted", "User food soft-deleted", deleted, startedAtNanos)
	}

	private fun logChange(event: String, message: String, food: UserFood, startedAtNanos: Long) {
		logger.atInfo()
			.addKeyValue("event", event)
			.addKeyValue("outcome", "success")
			.addKeyValue("userRef", food.userId.value)
			.addKeyValue("userFoodId", food.foodId.value)
			.addKeyValue("sourceType", food.source.type)
			.addKeyValue("basisUnit", food.nutritionBasis.unit)
			.addKeyValue("aliasCount", food.aliases.size)
			.addKeyValue("version", food.version)
			.addKeyValue("durationMs", elapsedMilliseconds(startedAtNanos))
			.log(message)
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
