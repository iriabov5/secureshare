package dev.secureshare

import dev.secureshare.sessions.ActiveSessions
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@org.junit.jupiter.api.extension.ExtendWith(org.springframework.boot.test.system.OutputCaptureExtension::class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("local")
class SessionManagementTest {
    @LocalServerPort private var port = 0
    @Autowired private lateinit var filterChain: org.springframework.security.web.FilterChainProxy
    private val mapper = jacksonObjectMapper()
    private val password = "Session correct passphrase"
    private fun browser() = Browser("http://localhost:$port")
    private fun register(browser: Browser): String {
        val username = "s" + UUID.randomUUID().toString().replace("-", "")
        assertEquals(201, browser.post(REGISTER, mapOf("username" to username, "password" to password, "_csrf" to browser.csrf())).statusCode())
        return username
    }
    private fun login(browser: Browser, username: String, secret: String = password) =
        browser.post(LOGIN, mapOf("username" to username, "password" to secret, "_csrf" to browser.csrf()))
    private fun list(browser: Browser) = mapper.readTree(browser.get(SESSIONS).body())
    private fun current(browser: Browser) = list(browser)["sessions"].first { it["current"].asBoolean() }["id"].asString()

    @Test @DisplayName("Список показывает только свои сеансы, публичные UUID и один current в каждом браузере")
    fun inventory() {
        val a = browser(); val user = register(a); val b = browser(); val other = browser()
        assertEquals(200, login(a, user).statusCode()); assertEquals(200, login(b, user).statusCode())
        assertEquals(200, login(other, register(other)).statusCode())
        val response = a.get(SESSIONS)
        assertEquals(200, response.statusCode())
        assertTrue(response.headers().firstValue("cache-control").orElse("").contains("no-store"))
        assertTrue(response.headers().firstValue("X-Request-ID").isPresent)
        val first = list(a); val second = list(b)
        assertEquals(3, first["maxSessions"].asInt())
        assertEquals(2, first["sessions"].size())
        assertEquals(1, first["sessions"].count { it["current"].asBoolean() })
        assertNotEquals(current(a), current(b))
        assertEquals(first["sessions"].toList().map { it["id"].asString() }.toSet(), second["sessions"].toList().map { it["id"].asString() }.toSet())
        assertEquals(1, list(other)["sessions"].size())
        for (session in first["sessions"]) {
            assertNotNull(UUID.fromString(session["id"].asString()))
            assertTrue(java.time.Instant.parse(session["createdAt"].asString()) <= java.time.Instant.parse(session["lastSeenAt"].asString()))
        }
        for (secret in listOf(a.sessionId(), b.sessionId(), password)) assertFalse(response.body().contains(secret))
    }

    @Test @DisplayName("Отзыв другого сеанса блокирует его следующий запрос и не завершает текущий")
    fun revocation(output: org.springframework.boot.test.system.CapturedOutput) {
        val a = browser(); val user = register(a); val b = browser()
        login(a, user); login(b, user)
        val target = current(b); val oldCookie = b.sessionId()
        assertEquals(204, a.delete("$SESSIONS/$target", a.csrf()).statusCode())
        val expired = b.get(TestEndpoints.ME)
        assertEquals(401, expired.statusCode()); assertTrue(expired.body().contains("SESSION_EXPIRED"))
        assertEquals(401, b.replay(oldCookie))
        assertTrue(output.all.contains("auth.session.revoked"))
        assertFalse(output.all.contains(oldCookie))
        assertFalse(output.all.contains(password))
        assertEquals(401, b.get(SESSIONS).statusCode())
        assertEquals(401, b.get("/api/v1/files").statusCode())
        assertEquals(1, list(a)["sessions"].size())
        assertEquals(404, a.delete("$SESSIONS/$target", a.csrf()).statusCode())
    }

    @Test @DisplayName("Чужой и неизвестный id неразличимы, текущий защищён, CSRF проверяется до отзыва")
    fun rejections() {
        val a = browser(); val user = register(a); login(a, user)
        val b = browser(); login(b, user)
        val other = browser(); login(other, register(other))
        val target = current(b)
        assertEquals(403, a.delete("$SESSIONS/$target").statusCode())
        assertEquals(403, a.delete("$SESSIONS/$target", "wrong").statusCode())
        val token = a.csrf()
        val foreign = a.delete("$SESSIONS/${current(other)}", token)
        val missing = a.delete("$SESSIONS/${UUID.randomUUID()}", token)
        assertEquals(404, foreign.statusCode()); assertEquals(missing.body(), foreign.body())
        assertEquals(409, a.delete("$SESSIONS/${current(a)}", token).statusCode())
        assertEquals(400, a.delete("$SESSIONS/not-a-uuid", token).statusCode())
        assertEquals(200, b.get(TestEndpoints.ME).statusCode())
        val anonymous = browser()
        assertEquals(401, anonymous.get(SESSIONS).statusCode())
        assertEquals(401, anonymous.delete("$SESSIONS/$target", anonymous.csrf()).statusCode())
        assertEquals(200, anonymous.get("/security").statusCode())
    }

    @Test @DisplayName("Четвёртый вход вытесняет LRU, повторный вход не занимает второй слот, logout освобождает слот")
    fun concurrencyAndReauthentication() {
        val a = browser(); val user = register(a)
        val b = browser(); val c = browser(); val d = browser()
        login(a, user); login(b, user); login(c, user)
        a.get(TestEndpoints.ME)
        assertEquals(200, login(d, user).statusCode())
        assertEquals(401, b.get(TestEndpoints.ME).statusCode())
        assertEquals(200, a.get(TestEndpoints.ME).statusCode())
        assertEquals(200, c.get(TestEndpoints.ME).statusCode())
        val old = d.sessionId()
        assertEquals(200, login(d, user).statusCode())
        assertNotEquals(old, d.sessionId()); assertEquals(401, d.replay(old))
        assertEquals(3, list(d)["sessions"].size())
        assertEquals(204, c.post(LOGOUT, mapOf("_csrf" to c.csrf())).statusCode())
        assertEquals(2, list(a)["sessions"].size())
        assertEquals(401, login(d, user, "incorrect password").statusCode())
        assertEquals(1, list(a)["sessions"].size())
    }

    @Test @DisplayName("Цепочка содержит один concurrent filter после form login и до авторизации")
    fun filterOrder() {
        val filters = checkNotNull(filterChain.getFilters("/api/v1/me/sessions"))
        fun index(type: Class<*>) = filters.indexOfFirst { type.isInstance(it) }
        val concurrent = index(org.springframework.security.web.session.ConcurrentSessionFilter::class.java)
        assertEquals(1, filters.count { it is org.springframework.security.web.session.ConcurrentSessionFilter })
        assertTrue(index(org.springframework.security.web.csrf.CsrfFilter::class.java) in 0 until concurrent)
        assertTrue(index(org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter::class.java) in 0 until concurrent)
        assertTrue(index(org.springframework.security.web.access.intercept.AuthorizationFilter::class.java) > concurrent)
    }

    @Test @DisplayName("Параллельные реальные входы соблюдают лимит трёх сеансов")
    fun parallelLogins() {
        val owner = browser(); val user = register(owner)
        val clients = List(6) { browser() }
        clients.forEach { it.csrf() }
        val gate = CyclicBarrier(clients.size)
        Executors.newFixedThreadPool(clients.size).use { executor ->
            val results = executor.invokeAll(clients.map { client -> Callable { gate.await(10, TimeUnit.SECONDS); login(client, user).statusCode() } })
            assertTrue(results.all { it.get() == 200 })
        }
        val active = clients.filter { it.get(TestEndpoints.ME).statusCode() == 200 }
        assertEquals(3, active.size)
        assertEquals(3, list(active.first())["sessions"].size())
    }

    @Test @DisplayName("Параллельный отзыв подтверждается только один раз")
    fun parallelRevocation() {
        val a = browser(); val user = register(a); val b = browser()
        login(a, user); login(b, user)
        val target = current(b); val token = a.csrf()
        Executors.newFixedThreadPool(2).use { executor ->
            val results = executor.invokeAll(List(2) { Callable { a.delete("$SESSIONS/$target", token).statusCode() } }).map { it.get() }.sorted()
            assertEquals(listOf(204, 404), results)
        }
        assertEquals(1, list(a)["sessions"].size())
    }

    companion object {
        private const val SESSIONS = "/api/v1/me/sessions"
        private const val REGISTER = "/api/v1/auth/register"
        private const val LOGIN = "/api/v1/auth/login"
        private const val LOGOUT = "/api/v1/auth/logout"
        @JvmStatic @DynamicPropertySource fun database(registry: DynamicPropertyRegistry) = TestDatabase.properties(registry)
    }
}
