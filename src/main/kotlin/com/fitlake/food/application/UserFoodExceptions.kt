package com.fitlake.food.application

import java.util.UUID

class UserFoodNotFoundException(foodId: UUID) : RuntimeException("User food was not found: $foodId")

class UserFoodValidationException(message: String) : RuntimeException(message)

class UserFoodConflictException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

class UserFoodPersistenceException(cause: Throwable) : RuntimeException("User food could not be persisted", cause)
