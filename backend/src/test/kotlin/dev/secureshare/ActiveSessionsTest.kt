package dev.secureshare

import dev.secureshare.security.AccountPrincipal
import dev.secureshare.sessions.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.mock.web.MockHttpSession
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.web.session.HttpSessionDestroyedEvent
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID

class ActiveSessionsTest {
    private class MutableClock(var now: Instant = Instant.parse("2026-11-06T06:00:00Z")) : Clock() {
        override fun instant(): Instant = now
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId): Clock = this
    }
    private fun principal(id: UUID = UUID.randomUUID(), name: String = "alice") = AccountPrincipal(id, name, "not-a-real-hash")
    private fun authenticate(registry: ActiveSessions, account: AccountPrincipal, request: MockHttpServletRequest = MockHttpServletRequest()): String {
        request.getSession(false)?.let { request.setRequestedSessionId(it.id); request.setRequestedSessionIdValid(true) }
        SessionAuthentication(registry).onAuthentication(UsernamePasswordAuthenticationToken.authenticated(account, null, account.authorities), request, MockHttpServletResponse())
        return request.getSession(false)!!.id
    }

    @Test @DisplayName("Публичные id отличаются от cookie, времена и сортировка управляются серверными часами")
    fun inventoryAndClock() {
        val clock = MutableClock(); val registry = ActiveSessions(clock); val account = principal()
        registry.registerNewSession("cookie-a", account)
        clock.now = clock.now.plusSeconds(30); registry.registerNewSession("cookie-b", account)
        clock.now = clock.now.plusSeconds(30); registry.refreshLastRequest("cookie-a")
        val list = registry.list(account, "cookie-b")
        assertEquals(listOf(true, false), list.sessions.map { it.current })
        assertEquals(Instant.parse("2026-11-06T06:01:00Z"), list.sessions.last().lastSeenAt)
        assertEquals(Instant.parse("2026-11-06T06:00:00Z"), list.sessions.last().createdAt)
        clock.now = clock.now.minusSeconds(120); registry.refreshLastRequest("cookie-a")
        assertEquals(list.sessions.last().lastSeenAt, registry.view("cookie-a").lastSeenAt)
        assertNotEquals(list.sessions[0].id, list.sessions[1].id)
        registry.refreshLastRequest("missing"); registry.removeSessionInformation("missing")
        assertEquals(2, registry.list(account, "cookie-b").sessions.size)
    }

    @Test @DisplayName("Logout/timeout очищают реестр по событию, новый процесс не содержит прежних сеансов")
    fun lifecycle() {
        val clock = MutableClock(); val registry = ActiveSessions(clock); val account = principal()
        val session = MockHttpSession(null, "cookie")
        registry.registerNewSession(session.id, account)
        registry.onApplicationEvent(HttpSessionDestroyedEvent(session))
        assertTrue(registry.list(account, "").sessions.isEmpty())
        assertNull(registry.getSessionInformation(session.id))
        registry.registerNewSession("remaining", account)
        assertTrue(ActiveSessions(clock).list(account, "remaining").sessions.isEmpty())
    }

    @Test @DisplayName("LRU использует активность, дату входа и стабильный публичный id при равенстве")
    fun lruTieBreak() {
        val clock = MutableClock(); val registry = ActiveSessions(clock); val account = principal()
        val ids = List(3) { authenticate(registry, account) }
        val ordered = ids.sortedBy { registry.view(it).id.toString() }
        authenticate(registry, account)
        assertTrue(registry.getSessionInformation(ordered.first())!!.isExpired)
        assertTrue(ordered.drop(1).all { !registry.getSessionInformation(it)!!.isExpired })
        val other = principal(name = "bob")
        val first = authenticate(registry, other)
        clock.now = clock.now.plusSeconds(10)
        val second = authenticate(registry, other)
        val third = authenticate(registry, other)
        registry.refreshLastRequest(first)
        authenticate(registry, other)
        // first и second имеют одинаковый lastSeenAt, но first создан раньше.
        assertTrue(registry.getSessionInformation(first)!!.isExpired)
        assertFalse(registry.getSessionInformation(second)!!.isExpired)
        assertFalse(registry.getSessionInformation(third)!!.isExpired)
    }

    @Test @DisplayName("Повторная аутентификация заменяет регистрацию и не оживляет отозванный сеанс")
    fun reauthenticationAndRevocation() {
        val registry = ActiveSessions(MutableClock()); val account = principal(); val request = MockHttpServletRequest()
        val old = authenticate(registry, account, request)
        val fresh = authenticate(registry, account, request)
        assertNotEquals(old, fresh); assertNull(registry.getSessionInformation(old))
        assertEquals(1, registry.list(account, fresh).sessions.size)
        registry.getSessionInformation(fresh)!!.expireNow()
        assertThrows(org.springframework.security.web.authentication.session.SessionAuthenticationException::class.java) { authenticate(registry, account, request) }
        assertTrue(registry.list(account, fresh).sessions.isEmpty())
    }

    @Test @DisplayName("Ошибка смены session ID не оставляет действующую HttpSession")
    fun fixationFailure() {
        val registry = ActiveSessions(MutableClock()); val account = principal()
        val request = object : MockHttpServletRequest() {
            override fun changeSessionId(): String = throw IllegalStateException("test fixation failure")
        }
        val existing = List(3) { authenticate(registry, account) }
        request.getSession()
        assertThrows(IllegalStateException::class.java) { authenticate(registry, account, request) }
        assertNull(request.getSession(false))
        assertEquals(3, registry.list(account, "").sessions.size)
        assertTrue(existing.none { registry.getSessionInformation(it)!!.isExpired })
    }

    @Test @DisplayName("Сравнение principal сохраняется после eraseCredentials, чужой id не даёт отзыва")
    fun ownershipAndIdentity() {
        val registry = ActiveSessions(MutableClock()); val account = principal(); val same = principal(account.accountId)
        assertEquals(account, same); assertEquals(account.hashCode(), same.hashCode())
        account.eraseCredentials(); assertEquals(account, same)
        registry.registerNewSession("a", account); registry.registerNewSession("b", same)
        val target = registry.view("b").id
        assertThrows(SessionNotFoundException::class.java) { registry.revoke(principal(name = "other"), "other", target) }
        assertThrows(CurrentSessionException::class.java) { registry.revoke(account, "b", target) }
        assertEquals(target, registry.revoke(account, "a", target).id)
        assertThrows(SessionNotFoundException::class.java) { registry.revoke(account, "a", target) }
        registry.removeSessionInformation("a")
        assertTrue(registry.list(account, "").sessions.isEmpty())
    }
    @Test @DisplayName("Гонка отзыва и повторного входа не восстанавливает отозванную регистрацию")
    fun concurrentReauthentication() {
        java.util.concurrent.Executors.newFixedThreadPool(2).use { executor ->
            repeat(20) {
                val registry = ActiveSessions(MutableClock()); val account = principal(); val request = MockHttpServletRequest()
                val old = authenticate(registry, account, request); val target = registry.view(old).id
                val barrier = java.util.concurrent.CyclicBarrier(2)
                val login = executor.submit(java.util.concurrent.Callable {
                    barrier.await()
                    try { authenticate(registry, account, request); true }
                    catch (_: org.springframework.security.web.authentication.session.SessionAuthenticationException) { false }
                })
                val revoke = executor.submit(java.util.concurrent.Callable {
                    barrier.await()
                    try { registry.revoke(account, "owner", target); true }
                    catch (_: SessionNotFoundException) { false }
                })
                assertNotEquals(login.get(), revoke.get())
                val active = registry.list(account, "").sessions
                assertEquals(if (login.get()) 1 else 0, active.size)
                registry.removeSessionInformation(request.getSession(false)!!.id)
                assertTrue(registry.allPrincipals.isEmpty())
            }
        }
    }

}
