package com.fitlake.auth.adapter

import com.fitlake.auth.application.CurrentUserProvider
import com.fitlake.user.application.UserQueryService
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
@RequestMapping("/api/me")
class MeController(
	private val currentUserProvider: CurrentUserProvider,
	private val userQueryService: UserQueryService,
) {
	@GetMapping
	fun me(): MeResponse {
		val authenticatedUser = currentUserProvider.requireCurrentUser()
		val account = userQueryService.requireById(authenticatedUser.userId)
		return MeResponse(
			userId = account.userId.value,
			email = account.email,
			displayName = account.displayName,
			timezone = account.timezone.id,
		)
	}
}

data class MeResponse(
	val userId: UUID,
	val email: String?,
	val displayName: String?,
	val timezone: String,
)
