package com.fitlake.auth.infrastructure.firebase

import com.fitlake.auth.application.AuthenticatedUser
import com.fitlake.auth.infrastructure.FirebaseAuthenticationToken
import com.fitlake.auth.infrastructure.RestAuthenticationEntryPoint
import com.fitlake.user.application.ProvisionUserCommand
import com.fitlake.user.application.UserAccountNotFoundException
import com.fitlake.user.application.UserProvisioningException
import com.fitlake.user.application.UserProvisioningService
import com.fitlake.user.domain.AuthProvider
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.security.core.context.SecurityContextHolder
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter

@Component
class FirebaseAuthenticationFilter(
	private val tokenVerifier: FirebaseTokenVerifier,
	private val userProvisioningService: UserProvisioningService,
	private val authenticationEntryPoint: RestAuthenticationEntryPoint,
) : OncePerRequestFilter() {
	override fun doFilterInternal(
		request: HttpServletRequest,
		response: HttpServletResponse,
		filterChain: FilterChain,
	) {
		val header = request.getHeader(AUTHORIZATION_HEADER)
		if (header == null || !header.startsWith(BEARER_PREFIX, ignoreCase = true)) {
			filterChain.doFilter(request, response)
			return
		}

		val idToken = header.substring(BEARER_PREFIX.length).trim()
		if (idToken.isEmpty() || idToken.any(Char::isWhitespace)) {
			authLogger.warn(
				"Firebase authentication rejected: method={}, path={}, reason=malformed_bearer_token",
				request.method,
				request.requestURI,
			)
			unauthorized(request, response)
			return
		}

		try {
			val claims = tokenVerifier.verify(idToken)
			val account = userProvisioningService.provision(
				ProvisionUserCommand(
					provider = AuthProvider.FIREBASE,
					issuer = claims.issuer,
					externalSubject = claims.subject,
					email = claims.email,
					emailVerified = claims.emailVerified,
					displayName = claims.displayName,
				),
			)

			val context = SecurityContextHolder.createEmptyContext()
			context.authentication = FirebaseAuthenticationToken.authenticated(
				AuthenticatedUser(
					userId = account.userId,
					externalSubject = claims.subject,
					email = claims.email,
				),
			)
			SecurityContextHolder.setContext(context)
			authLogger.debug(
				"Firebase authentication succeeded: method={}, path={}, issuer={}",
				request.method,
				request.requestURI,
				claims.issuer,
			)
			filterChain.doFilter(request, response)
		} catch (exception: FirebaseTokenVerificationException) {
			SecurityContextHolder.clearContext()
			authLogger.debug(
				"Firebase authentication rejected: method={}, path={}, reason=token_verification_failed",
				request.method,
				request.requestURI,
			)
			unauthorized(request, response)
		} catch (exception: IllegalArgumentException) {
			SecurityContextHolder.clearContext()
			authLogger.warn(
				"Firebase authentication rejected: method={}, path={}, reason=invalid_verified_claims",
				request.method,
				request.requestURI,
			)
			unauthorized(request, response)
		} catch (exception: UserAccountNotFoundException) {
			SecurityContextHolder.clearContext()
			authLogger.error(
				"Firebase authentication failed after token verification: reason=missing_internal_user",
			)
			internalServerError(response)
		} catch (exception: UserProvisioningException) {
			SecurityContextHolder.clearContext()
			authLogger.error(
				"Firebase authentication failed after token verification: reason=user_provisioning_failed",
			)
			internalServerError(response)
		}
	}

	private fun unauthorized(request: HttpServletRequest, response: HttpServletResponse) {
		authenticationEntryPoint.commence(
			request,
			response,
			FirebaseAuthenticationException(),
		)
	}

	private fun internalServerError(response: HttpServletResponse) {
		RestAuthenticationEntryPoint.writeJsonError(
			response,
			HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
			"internal_server_error",
		)
	}

	private class FirebaseAuthenticationException :
		org.springframework.security.core.AuthenticationException("Firebase authentication failed")

	companion object {
		private const val AUTHORIZATION_HEADER = "Authorization"
		private const val BEARER_PREFIX = "Bearer "
		private val authLogger = LoggerFactory.getLogger(FirebaseAuthenticationFilter::class.java)
	}
}
