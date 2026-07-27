package com.fitlake.auth.infrastructure.firebase

import jakarta.validation.constraints.NotBlank
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.validation.annotation.Validated

@Validated
@ConfigurationProperties("fitlake.firebase")
data class FirebaseProperties(
	@field:NotBlank
	val projectId: String,
)
