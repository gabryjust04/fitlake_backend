package com.fitlake.auth.infrastructure.firebase

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
		FirebaseApp.getApps()
			.firstOrNull { it.name == FirebaseApp.DEFAULT_APP_NAME }
			?.let {
				logger.info(
					"Reusing Firebase Admin app: appName={}, configuredProjectId={}",
					it.name,
					properties.projectId,
				)
				return it
			}

		val credentials = try {
			GoogleCredentials.getApplicationDefault()
		} catch (exception: Exception) {
			logger.error(
				"Firebase Application Default Credentials could not be loaded; " +
					"check GOOGLE_APPLICATION_CREDENTIALS (exceptionType={})",
				exception.javaClass.simpleName,
			)
			throw exception
		}

		logger.info(
			"Firebase Application Default Credentials loaded: credentialType={}, configuredProjectId={}",
			credentials.javaClass.simpleName,
			properties.projectId,
		)

		val options = FirebaseOptions.builder()
			.setCredentials(credentials)
			.setProjectId(properties.projectId)
			.build()

		return FirebaseApp.initializeApp(options).also {
			logger.info(
				"Firebase Admin initialized: appName={}, projectId={}",
				it.name,
				properties.projectId,
			)
		}
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
