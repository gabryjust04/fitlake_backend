package com.fitlake.food.application.port

import com.fitlake.food.application.UserFoodCandidate
import com.fitlake.food.application.UserFoodPage
import com.fitlake.food.application.UserFoodPageQuery
import com.fitlake.food.application.UserFoodSearchQuery
import com.fitlake.food.domain.UserFood
import com.fitlake.food.domain.UserFoodId
import com.fitlake.user.domain.UserId

interface UserFoodRepository {
	fun findActiveByIdAndUserId(foodId: UserFoodId, userId: UserId): UserFood?
	fun findActivePage(userId: UserId, query: UserFoodPageQuery): UserFoodPage
	fun existsActiveBarcode(userId: UserId, barcode: String, excludingFoodId: UserFoodId? = null): Boolean
	fun findConflictingActiveAlias(
		userId: UserId,
		normalizedAliases: Set<String>,
		excludingFoodId: UserFoodId? = null,
	): String?
	fun save(food: UserFood): UserFood
}

interface UserFoodSearchPort {
	fun search(userId: UserId, query: UserFoodSearchQuery): List<UserFoodCandidate>
}
