package com.fitlake.user.application.port

import com.fitlake.user.domain.UserAccount
import com.fitlake.user.domain.UserId

interface UserAccountRepository {
	fun findById(userId: UserId): UserAccount?

	fun save(userAccount: UserAccount): UserAccount
}
