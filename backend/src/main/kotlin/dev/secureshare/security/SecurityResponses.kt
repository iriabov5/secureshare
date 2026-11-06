package dev.secureshare.security

import tools.jackson.databind.ObjectMapper
import dev.secureshare.web.ApiError
import dev.secureshare.observability.*
import org.slf4j.event.Level
import dev.secureshare.web.toCurrentIdentity
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.security.authentication.InternalAuthenticationServiceException
import org.springframework.security.core.Authentication
import org.springframework.security.core.AuthenticationException
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.web.AuthenticationEntryPoint
import org.springframework.security.web.access.AccessDeniedHandler
import org.springframework.security.web.authentication.AuthenticationFailureHandler
import org.springframework.stereotype.Component

@Component
class SecurityResponses(private val mapper: ObjectMapper) : AuthenticationEntryPoint, AccessDeniedHandler, AuthenticationFailureHandler {
    override fun commence(request: HttpServletRequest, response: HttpServletResponse, exception: AuthenticationException) {
        AppLog.event(REQUEST_REJECTED, Outcome.REJECTED, Level.WARN, mapOf("reasonCode" to "UNAUTHENTICATED"))
        write(response, 401, ApiError("UNAUTHENTICATED", "Требуется вход"))
    }

    override fun handle(request: HttpServletRequest, response: HttpServletResponse, exception: org.springframework.security.access.AccessDeniedException) {
        val reason = if (exception is org.springframework.security.web.csrf.CsrfException) "MISSING_OR_INVALID_CSRF" else "FORBIDDEN"
        AppLog.event(REQUEST_REJECTED, Outcome.REJECTED, Level.WARN, mapOf("reasonCode" to reason))
        write(response, 403, ApiError("FORBIDDEN", "Доступ запрещён"))
    }

    // Неуспешный повторный вход отзывает и прежний Authentication.
    override fun onAuthenticationFailure(request: HttpServletRequest, response: HttpServletResponse, exception: AuthenticationException) {
        SecurityContextHolder.clearContext()
        request.getSession(false)?.invalidate()
        if (exception is InternalAuthenticationServiceException) {
            AppLog.failure(Operation.LOGIN, exception, Stage.USER_LOOKUP)
            write(response, 503, ApiError("SERVICE_UNAVAILABLE", "Сервис временно недоступен"))
        } else {
            AppLog.event("auth.login.rejected", Outcome.REJECTED, Level.WARN, mapOf("reasonCode" to "INVALID_CREDENTIALS"))
            write(response, 401, ApiError("INVALID_CREDENTIALS", "Неверный логин или пароль"))
        }
    }

    fun sessionExpired(response: HttpServletResponse) {
        AppLog.event(REQUEST_REJECTED, Outcome.REJECTED, Level.WARN, mapOf("reasonCode" to "SESSION_EXPIRED"))
        write(response, 401, ApiError("SESSION_EXPIRED", "Сеанс завершён. Войдите снова"))
    }

    fun authenticationSuccess(response: HttpServletResponse, authentication: Authentication) {
        AppLog.event("auth.login.succeeded", Outcome.SUCCESS, fields = mapOf("accountId" to (authentication.principal as AccountPrincipal).accountId))
        write(response, 200, (authentication.principal as AccountPrincipal).toCurrentIdentity())
    }

    private fun write(response: HttpServletResponse, status: Int, body: Any) {
        response.status = status
        response.contentType = "application/json"
        response.characterEncoding = "UTF-8"
        mapper.writeValue(response.writer, body)
    }
    private companion object { const val REQUEST_REJECTED = "security.request.rejected" }

}
