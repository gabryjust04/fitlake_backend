package com.fitlake.auth.infrastructure.firebase

import com.fitlake.auth.application.AuthenticatedUser
import com.fitlake.auth.infrastructure.FirebaseAuthenticationToken
import com.fitlake.auth.infrastructure.RestAuthenticationEntryPoint
import com.fitlake.shared.application.elapsedMilliseconds
import com.fitlake.shared.logging.sanitizedForTechnicalLogging
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

		val startedAtNanos = System.nanoTime()
		val idToken = header.substring(BEARER_PREFIX.length).trim()
		if (idToken.isEmpty() || idToken.any(Char::isWhitespace)) {
			authLogger.atWarn()
				.addKeyValue("event", "auth_token_verification_failed")
				.addKeyValue("outcome", "rejected")
				.addKeyValue("authProvider", AUTH_PROVIDER)
				.addKeyValue("method", request.method)
				.addKeyValue("errorCode", "MALFORMED_BEARER_TOKEN")
				.addKeyValue("durationMs", elapsedMilliseconds(startedAtNanos))
				.log("Firebase token verification rejected")
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
			authLogger.atDebug()
				.addKeyValue("event", "auth_user_resolved")
				.addKeyValue("outcome", "success")
				.addKeyValue("authProvider", AUTH_PROVIDER)
				.addKeyValue("userRef", account.userId.value)
				.addKeyValue("durationMs", elapsedMilliseconds(startedAtNanos))
				.log("Authenticated user resolved")
		} catch (exception: FirebaseTokenVerificationException) {
			SecurityContextHolder.clearContext()
			unauthorized(request, response)
			return
		} catch (exception: IllegalArgumentException) {
			SecurityContextHolder.clearContext()
			authLogger.atWarn()
				.addKeyValue("event", "auth_user_resolution_failed")
				.addKeyValue("outcome", "rejected")
				.addKeyValue("authProvider", AUTH_PROVIDER)
				.addKeyValue("errorCode", "INVALID_VERIFIED_CLAIMS")
				.addKeyValue("durationMs", elapsedMilliseconds(startedAtNanos))
				.log("Authenticated user resolution rejected")
			unauthorized(request, response)
			return
		} catch (exception: UserAccountNotFoundException) {
			SecurityContextHolder.clearContext()
			authLogger.atError()
				.addKeyValue("event", "auth_user_resolution_failed")
				.addKeyValue("outcome", "failure")
				.addKeyValue("authProvider", AUTH_PROVIDER)
				.addKeyValue("errorCode", "MISSING_INTERNAL_USER")
				.addKeyValue("exceptionType", exception.javaClass.simpleName)
				.addKeyValue("durationMs", elapsedMilliseconds(startedAtNanos))
				.setCause(exception.sanitizedForTechnicalLogging())
				.log("Authenticated user resolution failed")
			internalServerError(response)
			return
		} catch (exception: UserProvisioningException) {
			SecurityContextHolder.clearContext()
			authLogger.atError()
				.addKeyValue("event", "auth_user_resolution_failed")
				.addKeyValue("outcome", "failure")
				.addKeyValue("authProvider", AUTH_PROVIDER)
				.addKeyValue("errorCode", "USER_PROVISIONING_FAILED")
				.addKeyValue("exceptionType", exception.javaClass.simpleName)
				.addKeyValue("durationMs", elapsedMilliseconds(startedAtNanos))
				.setCause(exception.sanitizedForTechnicalLogging())
				.log("Authenticated user resolution failed")
			internalServerError(response)
			return
		} catch (exception: RuntimeException) {
			SecurityContextHolder.clearContext()
			authLogger.atError()
				.addKeyValue("event", "auth_user_resolution_failed")
				.addKeyValue("outcome", "failure")
				.addKeyValue("authProvider", AUTH_PROVIDER)
				.addKeyValue("errorCode", "AUTHENTICATION_PROCESSING_FAILED")
				.addKeyValue("exceptionType", exception.javaClass.simpleName)
				.addKeyValue("durationMs", elapsedMilliseconds(startedAtNanos))
				.setCause(exception.sanitizedForTechnicalLogging())
				.log("Authenticated user resolution failed")
			internalServerError(response)
			return
		}

		filterChain.doFilter(request, response)
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
		private const val AUTH_PROVIDER = "firebase"
		private val authLogger = LoggerFactory.getLogger(FirebaseAuthenticationFilter::class.java)
	}
}
