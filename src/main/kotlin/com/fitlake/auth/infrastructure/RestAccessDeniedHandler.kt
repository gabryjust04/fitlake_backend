package com.fitlake.auth.infrastructure

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.security.access.AccessDeniedException
import org.springframework.security.web.access.AccessDeniedHandler
import org.springframework.stereotype.Component

@Component
class RestAccessDeniedHandler : AccessDeniedHandler {
	override fun handle(
		request: HttpServletRequest,
		response: HttpServletResponse,
		accessDeniedException: AccessDeniedException,
	) {
		RestAuthenticationEntryPoint.writeJsonError(
			response,
			HttpServletResponse.SC_FORBIDDEN,
			"forbidden",
		)
	}
}
