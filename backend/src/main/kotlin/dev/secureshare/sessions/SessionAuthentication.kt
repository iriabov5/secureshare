package dev.secureshare.sessions

import dev.secureshare.observability.AppLog
import dev.secureshare.observability.Outcome
import dev.secureshare.security.AccountPrincipal
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.security.core.Authentication
import org.springframework.security.core.session.SessionInformation
import org.springframework.security.core.session.SessionRegistry
import org.springframework.security.web.authentication.session.ChangeSessionIdAuthenticationStrategy
import org.springframework.security.web.authentication.session.CompositeSessionAuthenticationStrategy
import org.springframework.security.web.authentication.session.ConcurrentSessionControlAuthenticationStrategy
import org.springframework.security.web.authentication.session.RegisterSessionAuthenticationStrategy
import org.springframework.security.web.authentication.session.SessionAuthenticationException
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy

class SessionAuthentication(private val sessions: ActiveSessions) : SessionAuthenticationStrategy {
    private fun strategy(victims: MutableList<SessionInformation>): SessionAuthenticationStrategy {
        val concurrent = object : ConcurrentSessionControlAuthenticationStrategy(sessions) {
            init { setMaximumSessions(ActiveSessions.LIMIT) }

            override fun allowableSessionsExceeded(active: MutableList<SessionInformation>, allowableSessions: Int, registry: SessionRegistry) {
                victims.addAll(active.sortedWith(compareBy<SessionInformation> { sessions.view(it.sessionId).lastSeenAt }
                    .thenBy { sessions.view(it.sessionId).createdAt }.thenBy { sessions.view(it.sessionId).id.toString() })
                    .take(active.size - allowableSessions + 1))
            }
        }
        return CompositeSessionAuthenticationStrategy(listOf(
            concurrent, ChangeSessionIdAuthenticationStrategy(), RegisterSessionAuthenticationStrategy(sessions),
        ))
    }

    private fun expire(victim: SessionInformation) {
        victim.expireNow()
        AppLog.event("auth.session.expired", Outcome.SUCCESS, fields = mapOf(
            "accountId" to (victim.principal as AccountPrincipal).accountId,
            "targetSessionId" to sessions.view(victim.sessionId).id, "reason" to "CONCURRENCY_LIMIT",
        ))
    }

    override fun onAuthentication(authentication: Authentication, request: HttpServletRequest, response: HttpServletResponse) {
        val account = authentication.principal as AccountPrincipal
        val previous = request.getSession(false)?.id
        val previousAccount = previous?.let { sessions.getSessionInformation(it)?.principal as? AccountPrincipal }
        sessions.locked(listOfNotNull(account.accountId, previousAccount?.accountId)) {
            // Credentials могли проверяться параллельно с отзывом исходной сессии.
            if (previous != null && sessions.getSessionInformation(previous)?.isExpired == true) {
                throw SessionAuthenticationException("Session expired")
            }
            try {
                // Не отзываем другие сеансы, пока новая регистрация не завершилась.
                val victims = mutableListOf<SessionInformation>()
                strategy(victims).onAuthentication(authentication, request, response)
                if (previous != null && previous != request.getSession(false)?.id) sessions.removeSessionInformation(previous)
                victims.forEach(::expire)
            } catch (exception: Exception) {
                request.getSession(false)?.invalidate()
                throw exception
            }
        }
    }
}
