package com.fitlake.user.application

import com.fitlake.user.application.port.UserAccountRepository
import com.fitlake.user.domain.UserAccount
import com.fitlake.user.domain.UserId
import org.springframework.stereotype.Service

@Service
class UserQueryService(
	private val userAccountRepository: UserAccountRepository,
) {
	fun requireById(userId: UserId): UserAccount =
		userAccountRepository.findById(userId) ?: throw UserAccountNotFoundException(userId)
}
