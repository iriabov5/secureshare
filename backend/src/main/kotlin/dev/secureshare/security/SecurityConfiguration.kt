package dev.secureshare.security

import dev.secureshare.identity.BoundedPasswordEncoder
import dev.secureshare.observability.AppLog
import dev.secureshare.observability.Outcome
import jakarta.servlet.DispatcherType
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpMethod
import org.springframework.security.authentication.dao.DaoAuthenticationProvider
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import org.springframework.security.crypto.password.DelegatingPasswordEncoder
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher
import java.time.Clock

@Configuration
@EnableMethodSecurity
class SecurityConfiguration {
    @Bean fun clock(): Clock = Clock.systemUTC()
    @Bean fun passwordEncoder(): PasswordEncoder = BoundedPasswordEncoder(
        DelegatingPasswordEncoder("bcrypt", mapOf("bcrypt" to BCryptPasswordEncoder(12))),
    )
    @Bean fun authenticationProvider(details: AccountDetailsService, encoder: PasswordEncoder): DaoAuthenticationProvider =
        DaoAuthenticationProvider(details).also { it.setPasswordEncoder(encoder) }

    @Bean
    fun securityFilterChain(http: HttpSecurity, responses: SecurityResponses, provider: DaoAuthenticationProvider): SecurityFilterChain {
        http.authenticationProvider(provider)
            .authorizeHttpRequests {
                it.dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                    .requestMatchers(HttpMethod.GET, "/", "/login", "/register", "/app", "/files", "/index.html", "/assets/**", "/favicon.svg", "/api/v1/auth/csrf").permitAll()
                    .requestMatchers(HttpMethod.POST, "/api/v1/auth/register", "/api/v1/auth/login", "/api/v1/auth/logout").permitAll()
                    .requestMatchers(HttpMethod.GET, "/api/v1/me", "/api/v1/files", "/api/v1/files/*/content").authenticated()
                    .requestMatchers(HttpMethod.POST, "/api/v1/files").authenticated()
                    .anyRequest().denyAll()
            }
            .formLogin {
                it.loginPage("/login").loginProcessingUrl("/api/v1/auth/login")
                    .successHandler { _, response, authentication -> responses.authenticationSuccess(response, authentication) }
                    .failureHandler(responses)
            }
            .logout {
                it.logoutRequestMatcher(PathPatternRequestMatcher.withDefaults().matcher(HttpMethod.POST, "/api/v1/auth/logout"))
                    .logoutSuccessHandler { _, response, authentication ->
                        val id = (authentication?.principal as? AccountPrincipal)?.accountId
                        AppLog.event("auth.logout.succeeded", Outcome.SUCCESS, fields = if (id != null) mapOf("accountId" to id) else emptyMap())
                        response.status = 204
                    }
                    .invalidateHttpSession(true).clearAuthentication(true).deleteCookies("JSESSIONID")
            }
            .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED).sessionFixation { fixation -> fixation.changeSessionId() } }
            .requestCache { it.disable() }
            .exceptionHandling { it.authenticationEntryPoint(responses).accessDeniedHandler(responses) }
            .headers {
                it.referrerPolicy { policy -> policy.policy(ReferrerPolicyHeaderWriter.ReferrerPolicy.NO_REFERRER) }
                it.contentSecurityPolicy { csp -> csp.policyDirectives("default-src 'self'; frame-ancestors 'none'; form-action 'self'; base-uri 'self'") }
            }
        return http.build()
    }
}
