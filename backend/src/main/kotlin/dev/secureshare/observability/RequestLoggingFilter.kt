package dev.secureshare.observability

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.MDC
import org.slf4j.event.Level
import org.springframework.boot.web.servlet.FilterRegistrationBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.Ordered
import org.springframework.web.filter.OncePerRequestFilter
import org.springframework.web.servlet.HandlerMapping
import java.io.IOException
import java.util.UUID

class RequestLoggingFilter : OncePerRequestFilter() {
    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, chain: FilterChain) {
        val previous = MDC.getCopyOfContextMap()
        val requestId = UUID.randomUUID().toString()
        val started = System.nanoTime()
        request.setAttribute(REQUEST_ID, requestId)
        response.setHeader(HEADER, requestId)
        MDC.clear()
        MDC.put("requestId", requestId)
        var outcome: Outcome? = null
        try {
            AppLog.event("http.request.started", level = Level.DEBUG, fields = mapOf("method" to safeMethod(request.method), "route" to route(request)))
            chain.doFilter(request, response)
        } catch (exception: Exception) {
            outcome = handleFailure(response, exception)
        } finally {
            try { completed(request, response, started, outcome) }
            finally {
                MDC.clear()
                if (previous != null) MDC.setContextMap(previous)
            }
        }
    }

    private fun handleFailure(response: HttpServletResponse, exception: Exception): Outcome {
        if (exception is IOException && response.isCommitted) return Outcome.CLIENT_DISCONNECTED
        AppLog.failure(Operation.HTTP, exception)
        if (response.isCommitted) return Outcome.ERROR
        response.resetBuffer()
        response.status = 500
        response.contentType = "application/json"
        response.characterEncoding = "UTF-8"
        return try {
            response.writer.write("{\"code\":\"INTERNAL_ERROR\",\"message\":\"Внутренняя ошибка сервиса\"}")
            Outcome.ERROR
        } catch (_: IOException) { Outcome.CLIENT_DISCONNECTED }
    }

    private fun completed(request: HttpServletRequest, response: HttpServletResponse, started: Long, outcome: Outcome?) {
        val status = response.status
        val result = outcome ?: statusOutcome(status)
        val route = route(request)
        @Suppress("UNCHECKED_CAST")
        val download = request.getAttribute(DOWNLOAD) as? Map<String, Any>
        if (result == Outcome.SUCCESS && download != null) AppLog.event("file.download.succeeded", Outcome.SUCCESS, fields = download)
        AppLog.event("http.request.completed", result, logLevel(status, result, route), mapOf(
            "method" to safeMethod(request.method), "route" to route, "status" to status,
            "durationMs" to (System.nanoTime() - started) / 1_000_000,
        ))
    }

    private fun statusOutcome(status: Int) = when {
        status >= 500 -> Outcome.ERROR
        status >= 400 -> Outcome.REJECTED
        else -> Outcome.SUCCESS
    }

    private fun logLevel(status: Int, outcome: Outcome, route: String) = when {
        status >= 500 || outcome == Outcome.ERROR -> Level.ERROR
        status >= 400 || outcome == Outcome.CLIENT_DISCONNECTED -> Level.WARN
        route in QUIET_ROUTES -> Level.DEBUG
        else -> Level.INFO
    }

    private fun route(request: HttpServletRequest): String {
        val pattern = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE)?.toString()
        if (pattern in ROUTES) return pattern!!
        val path = request.servletPath.ifBlank { request.requestURI }
        return when {
            path in ROUTES -> path
            path.startsWith("/assets/") -> ASSETS_PATTERN
            path.matches(Regex("/api/v1/me/sessions/[0-9a-fA-F-]{36}")) -> "/api/v1/me/sessions/{id}"
            path.matches(Regex("/api/v1/files/[0-9a-fA-F-]{36}/content")) -> "/api/v1/files/{id}/content"
            else -> "UNMATCHED"
        }
    }

    private fun safeMethod(value: String) = if (value in METHODS) value else "OTHER"

    companion object {
        const val DOWNLOAD = "secureshare.download"
        const val HEADER = "X-Request-ID"
        const val REQUEST_ID = "secureshare.requestId"
        private const val ASSETS_PATTERN = "/assets/**"
        private val QUIET_ROUTES = setOf(ASSETS_PATTERN, "/favicon.svg", "/api/v1/auth/csrf")
        private val METHODS = setOf("GET", "POST", "PUT", "DELETE", "PATCH", "HEAD", "OPTIONS", "TRACE")
        private val ROUTES = setOf("/", "/login", "/register", "/files", "/security", "/app", "/index.html", "/favicon.svg", ASSETS_PATTERN,
            "/api/v1/auth/csrf", "/api/v1/auth/login", "/api/v1/auth/logout", "/api/v1/auth/register", "/api/v1/me", "/api/v1/me/sessions", "/api/v1/me/sessions/{id}", "/api/v1/files", "/api/v1/files/{id}/content")
    }
}

@Configuration
class RequestLoggingConfiguration {
    @Bean
    fun requestLoggingFilter() = FilterRegistrationBean(RequestLoggingFilter()).apply {
        order = Ordered.HIGHEST_PRECEDENCE + 10
        setDispatcherTypes(jakarta.servlet.DispatcherType.REQUEST)
    }
}
