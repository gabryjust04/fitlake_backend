package com.fitlake.user.application

import com.fitlake.user.domain.UserId

class UserAccountNotFoundException(userId: UserId) : RuntimeException(
	"Internal user account not found for user ID $userId",
)
