package com.fitlake.auth.infrastructure

import io.swagger.v3.oas.models.Components
import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.info.Info
import io.swagger.v3.oas.models.security.SecurityRequirement
import io.swagger.v3.oas.models.security.SecurityScheme
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration(proxyBeanMethods = false)
class OpenApiConfig {
	@Bean
	fun fitLakeOpenApi(): OpenAPI = OpenAPI()
		.info(
			Info()
				.title("FitLake API")
				.description("FitLake personal daily tracking API")
				.version("v1"),
		)
		.components(
			Components().addSecuritySchemes(
				FIREBASE_BEARER_SCHEME,
				SecurityScheme()
					.type(SecurityScheme.Type.HTTP)
					.scheme("bearer")
					.bearerFormat("Firebase ID token")
					.description("Firebase ID token returned by the authenticated client"),
			),
		)
		.addSecurityItem(SecurityRequirement().addList(FIREBASE_BEARER_SCHEME))

	companion object {
		const val FIREBASE_BEARER_SCHEME = "firebaseBearer"
	}
}
