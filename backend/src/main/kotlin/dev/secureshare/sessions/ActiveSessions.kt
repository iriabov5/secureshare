package dev.secureshare.sessions

import dev.secureshare.security.AccountPrincipal
import org.springframework.security.core.session.SessionRegistryImpl
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

data class SessionView(val id: UUID, val current: Boolean, val createdAt: Instant, val lastSeenAt: Instant)
data class SessionList(val sessions: List<SessionView>, val maxSessions: Int = ActiveSessions.LIMIT)

@Component
class ActiveSessions(private val clock: Clock) : SessionRegistryImpl() {
    private data class Details(val id: UUID, val createdAt: Instant, var lastSeenAt: Instant)
    private val details = ConcurrentHashMap<String, Details>()
    // Полосы блокировок ограничивают память и не удаляются, пока другой поток ожидает входа.
    private val locks = Array(64) { ReentrantLock() }

    fun <T> locked(accounts: List<UUID>, action: () -> T): T {
        val indexes = accounts.map { (it.hashCode() and Int.MAX_VALUE) % locks.size }.distinct().sorted()
        fun enter(index: Int): T = if (index == indexes.size) action() else locks[indexes[index]].withLock { enter(index + 1) }
        return enter(0)
    }

    override fun registerNewSession(sessionId: String, principal: Any) {
        val account = principal as AccountPrincipal
        locked(listOf(account.accountId)) {
            super.registerNewSession(sessionId, principal)
            val now = clock.instant()
            details[sessionId] = Details(UUID.randomUUID(), now, now)
        }
    }

    override fun removeSessionInformation(sessionId: String) {
        val account = getSessionInformation(sessionId)?.principal as? AccountPrincipal ?: return
        locked(listOf(account.accountId)) {
            super.removeSessionInformation(sessionId)
            details.remove(sessionId)
        }
    }

    override fun refreshLastRequest(sessionId: String) {
        val account = getSessionInformation(sessionId)?.principal as? AccountPrincipal ?: return
        locked(listOf(account.accountId)) {
            super.refreshLastRequest(sessionId)
            details[sessionId]?.let { it.lastSeenAt = maxOf(it.lastSeenAt, clock.instant()) }
        }
    }

    fun list(account: AccountPrincipal, currentId: String): SessionList = locked(listOf(account.accountId)) {
        SessionList(getAllSessions(account, false).map { view(it.sessionId, currentId) }
            .sortedWith(compareByDescending<SessionView> { it.current }.thenByDescending { it.lastSeenAt }.thenBy { it.id.toString() }))
    }

    fun view(sessionId: String, currentId: String = ""): SessionView {
        val value = checkNotNull(details[sessionId])
        return SessionView(value.id, sessionId == currentId, value.createdAt, value.lastSeenAt)
    }

    fun revoke(account: AccountPrincipal, currentId: String, id: UUID): SessionView = locked(listOf(account.accountId)) {
        val target = getAllSessions(account, false).firstOrNull { details[it.sessionId]?.id == id }
            ?: throw SessionNotFoundException()
        if (target.sessionId == currentId) throw CurrentSessionException()
        val result = view(target.sessionId)
        target.expireNow()
        result
    }

    companion object { const val LIMIT = 3 }
}
