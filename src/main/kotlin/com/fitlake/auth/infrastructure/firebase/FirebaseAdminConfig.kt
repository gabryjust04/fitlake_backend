package com.fitlake.auth.infrastructure.firebase

import com.fitlake.shared.application.elapsedMilliseconds
import com.google.auth.oauth2.GoogleCredentials
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.auth.FirebaseAuth
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.slf4j.LoggerFactory

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(FirebaseProperties::class)
class FirebaseAdminConfig {

	@Bean
	fun firebaseApp(properties: FirebaseProperties): FirebaseApp {
		val startedAtNanos = System.nanoTime()
		FirebaseApp.getApps()
			.firstOrNull { it.name == FirebaseApp.DEFAULT_APP_NAME }
			?.let {
				logger.atInfo()
					.addKeyValue("event", "firebase_admin_initialized")
					.addKeyValue("outcome", "success")
					.addKeyValue("mode", "reused")
					.addKeyValue("appName", it.name)
					.addKeyValue("durationMs", elapsedMilliseconds(startedAtNanos))
					.log("Firebase Admin initialized")
				return it
			}

		val credentials = try {
			GoogleCredentials.getApplicationDefault()
		} catch (exception: Exception) {
			initializationFailure(
				"APPLICATION_DEFAULT_CREDENTIALS_UNAVAILABLE",
				exception,
				startedAtNanos,
			)
		}

		val initialized = try {
			val options = FirebaseOptions.builder()
				.setCredentials(credentials)
				.setProjectId(properties.projectId)
				.build()
			FirebaseApp.initializeApp(options)
		} catch (exception: Exception) {
			initializationFailure("FIREBASE_ADMIN_INITIALIZATION_FAILED", exception, startedAtNanos)
		}

		return initialized.also {
			logger.atInfo()
				.addKeyValue("event", "firebase_admin_initialized")
				.addKeyValue("outcome", "success")
				.addKeyValue("mode", "created")
				.addKeyValue("appName", it.name)
				.addKeyValue("credentialType", credentials.javaClass.simpleName)
				.addKeyValue("durationMs", elapsedMilliseconds(startedAtNanos))
				.log("Firebase Admin initialized")
		}
	}

	internal fun initializationFailure(
		errorCode: String,
		exception: Exception,
		startedAtNanos: Long,
	): Nothing {
		logger.atError()
			.addKeyValue("event", "firebase_admin_initialization_failed")
			.addKeyValue("outcome", "failure")
			.addKeyValue("errorCode", errorCode)
			.addKeyValue("exceptionType", exception.javaClass.simpleName)
			.addKeyValue("durationMs", elapsedMilliseconds(startedAtNanos))
			.log("Firebase Admin initialization failed")
		throw FirebaseAdminInitializationException(errorCode, exception.stackTrace)
	}

	@Bean
	fun firebaseAuth(firebaseApp: FirebaseApp): FirebaseAuth = FirebaseAuth.getInstance(firebaseApp)

	@Bean
	fun firebaseTokenVerifier(firebaseAuth: FirebaseAuth): FirebaseTokenVerifier =
		FirebaseAdminTokenVerifier(firebaseAuth)

	companion object {
		private val logger = LoggerFactory.getLogger(FirebaseAdminConfig::class.java)
	}
}

internal class FirebaseAdminInitializationException(
	errorCode: String,
	originalStackTrace: Array<StackTraceElement>,
) : IllegalStateException("Firebase Admin initialization failed ($errorCode)") {
	init {
		stackTrace = originalStackTrace
	}
}
