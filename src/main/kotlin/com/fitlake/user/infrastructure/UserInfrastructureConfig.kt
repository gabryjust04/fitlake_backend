package com.fitlake.user.infrastructure

import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock
import java.time.ZoneId

@Configuration(proxyBeanMethods = false)
class UserInfrastructureConfig {

	@Bean
	fun applicationClock(): Clock = Clock.systemUTC()

	@Bean
	fun defaultUserTimezone(
		@Value("\${fitlake.user.default-timezone:Europe/Rome}") timezone: String,
	): ZoneId = ZoneId.of(timezone)
}
