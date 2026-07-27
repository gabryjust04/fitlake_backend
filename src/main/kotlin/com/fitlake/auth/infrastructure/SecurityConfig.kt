package com.fitlake.auth.infrastructure

import com.fitlake.auth.infrastructure.firebase.FirebaseAuthenticationFilter
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.security.config.Customizer
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter

@Configuration(proxyBeanMethods = false)
class SecurityConfig {

	@Bean
	fun securityFilterChain(
		http: HttpSecurity,
		firebaseAuthenticationFilter: FirebaseAuthenticationFilter,
		authenticationEntryPoint: RestAuthenticationEntryPoint,
		accessDeniedHandler: RestAccessDeniedHandler,
	): SecurityFilterChain {
		http
			.csrf { it.disable() }
			.cors(Customizer.withDefaults())
			.sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
			.requestCache { it.disable() }
			.formLogin { it.disable() }
			.httpBasic { it.disable() }
			.logout { it.disable() }
			.exceptionHandling {
				it.authenticationEntryPoint(authenticationEntryPoint)
				it.accessDeniedHandler(accessDeniedHandler)
			}
			.authorizeHttpRequests {
				it.requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
				it.requestMatchers(
					"/v3/api-docs/**",
					"/swagger-ui.html",
					"/swagger-ui/**",
				).permitAll()
				it.requestMatchers("/api/**").authenticated()
				it.anyRequest().denyAll()
			}
			.addFilterBefore(firebaseAuthenticationFilter, UsernamePasswordAuthenticationFilter::class.java)

		return http.build()
	}
}
