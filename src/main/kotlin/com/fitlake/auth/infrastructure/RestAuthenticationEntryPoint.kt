package com.fitlake.auth.infrastructure

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.MediaType
import org.springframework.security.core.AuthenticationException
import org.springframework.security.web.AuthenticationEntryPoint
import org.springframework.stereotype.Component

@Component
class RestAuthenticationEntryPoint : AuthenticationEntryPoint {
	override fun commence(
		request: HttpServletRequest,
		response: HttpServletResponse,
		authException: AuthenticationException,
	) {
		writeJsonError(response, HttpServletResponse.SC_UNAUTHORIZED, "unauthorized")
	}

	companion object {
		fun writeJsonError(response: HttpServletResponse, status: Int, error: String) {
			response.status = status
			response.contentType = MediaType.APPLICATION_JSON_VALUE
			response.characterEncoding = Charsets.UTF_8.name()
			response.writer.write("{\"error\":\"$error\"}")
		}
	}
}
