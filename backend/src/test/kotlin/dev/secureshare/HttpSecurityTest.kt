package dev.secureshare

import org.springframework.http.HttpHeaders

import dev.secureshare.TestEndpoints.REGISTER
import dev.secureshare.TestEndpoints.LOGIN
import dev.secureshare.TestEndpoints.LOGOUT
import dev.secureshare.TestEndpoints.ME
import dev.secureshare.TestEndpoints.APP
import dev.secureshare.TestEndpoints.UNLISTED_API

import dev.secureshare.identity.AccountRepository
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.*
import java.util.*
import java.util.concurrent.Callable
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@org.junit.jupiter.api.extension.ExtendWith(org.springframework.boot.test.system.OutputCaptureExtension::class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@ActiveProfiles("local")
class HttpSecurityTest {
    @LocalServerPort private var port = 0
    @Autowired private lateinit var accounts: AccountRepository
    @Autowired private lateinit var encoder: PasswordEncoder
    @Autowired private lateinit var mvc: MockMvc
    @Autowired private lateinit var jdbc: JdbcTemplate
    private val password = "Correct passphrase 2026"
    private fun browser() = Browser("http://localhost:$port")
    private fun name() = "u" + UUID.randomUUID().toString().replace("-", "")
    private fun register(b: Browser, username: String = name()): String {
        assertEquals(201, b.post(REGISTER, mapOf("username" to username, "password" to password, "_csrf" to b.csrf())).statusCode())
        return username
    }
    private fun login(b: Browser, username: String): java.net.http.HttpResponse<String> =
        b.post(LOGIN, mapOf("username" to username, "password" to password, "_csrf" to b.csrf()))

    @Test
    @DisplayName("Регистрация хранит USER и bcrypt, не выполняя автоматический вход")
    fun registerAndIdentity(output: org.springframework.boot.test.system.CapturedOutput) {
        val b = browser()
        val username = register(b).uppercase()
        val stored = accounts.findByUsername(username.lowercase())!!
        assertEquals("USER", stored.role)
        assertTrue(encoder.matches(password, stored.passwordHash))
        assertEquals(401, b.get(ME).statusCode())
        assertEquals(200, login(b, username).statusCode())
        val me = b.get("/api/v1/me?username=another&role=ADMIN&id=forged")
        assertEquals(200, me.statusCode())
        assertTrue(me.body().contains(stored.id.toString()))
        assertTrue(me.body().contains(username.lowercase()))
        assertFalse(me.body().contains("ADMIN"))
        assertFalse(me.body().contains("password"))
        assertFalse(me.body().contains(stored.passwordHash))
        assertFalse(b.get(APP).body().contains(username.lowercase()))
        assertFalse(output.all.contains(password))
        assertFalse(output.all.contains(stored.passwordHash))
        assertFalse(output.all.contains(b.sessionId()))
    }

    @Test
    @DisplayName("Вход меняет session ID, старый ID больше не авторизует; logout отзывает новую сессию")
    fun fixationAndLogout() {
        val b = browser(); val username = register(b)
        assertEquals(401, b.get("/private").statusCode())
        val csrf = b.csrf(); val oldId = b.sessionId()
        val response = b.post(LOGIN + "?next=https://example.com", mapOf("username" to username, "password" to password, "_csrf" to csrf))
        assertEquals(200, response.statusCode())
        assertFalse(response.headers().firstValue("location").isPresent)
        val authenticatedId = b.sessionId()
        assertNotEquals(oldId, authenticatedId)
        assertEquals(401, b.replay(oldId))
        assertEquals(200, b.get(ME).statusCode())
        assertEquals(403, b.post(LOGOUT, mapOf("_csrf" to csrf)).statusCode())
        assertEquals(200, b.get(ME).statusCode())
        val logoutToken = b.csrf()
        val logout = b.post(LOGOUT, mapOf("_csrf" to logoutToken))
        assertEquals(204, logout.statusCode())
        assertTrue(logout.headers().allValues(HttpHeaders.SET_COOKIE).flatMap(java.net.HttpCookie::parse).any {
            it.name == "JSESSIONID" && it.maxAge == 0L && it.path == "/"
        })
        assertEquals(401, b.replay(authenticatedId))
        assertEquals(401, b.get(ME).statusCode())
        assertEquals(200, b.get(APP).statusCode())
        assertEquals(403, b.post(LOGOUT, mapOf("_csrf" to logoutToken)).statusCode())
    }

    @Test
    @DisplayName("Неверные и неизвестные credentials дают одинаковый отказ и очищают прежний вход")
    fun rejectedLogin() {
        val b = browser(); val username = register(b); login(b, username)
        for ((user, pass) in listOf(username to "wrong passphrase", name() to password, "" to "", username to "x".repeat(100))) {
            val result = b.post(LOGIN, mapOf("username" to user, "password" to pass, "_csrf" to b.csrf()))
            assertEquals(401, result.statusCode())
            assertTrue(result.body().contains("INVALID_CREDENTIALS"))
            assertEquals(401, b.get(ME).statusCode())
        }

    }

    @Test
    @DisplayName("Нетокенизированные и чужие CSRF отклоняются на всех трёх формах без побочных эффектов")
    fun csrfRejections() {
        val b = browser(); val foreign = browser().csrf()
        val username = name()
        for (path in listOf(REGISTER, LOGIN, LOGOUT)) {
            for (token in listOf<String?>(null, foreign)) {
                val fields = mapOf("username" to username, "password" to password) +
                    if (token != null) mapOf("_csrf" to token) else emptyMap()
                assertEquals(403, b.post(path, fields).statusCode())
            }
        }
        assertNull(accounts.findByUsername(username))
        assertEquals(401, b.get(ME).statusCode())
        register(b, username); login(b, username)
        assertEquals(403, b.post(LOGOUT, emptyMap()).statusCode())
        assertEquals(403, b.post(LOGOUT, mapOf("_csrf" to foreign)).statusCode())
        assertEquals(200, b.get(ME).statusCode())
        assertEquals(403, b.get(LOGOUT).statusCode())
        assertEquals(200, b.get(ME).statusCode())
    }

    @Test
    @DisplayName("Невалидная регистрация не создаёт запись и не отражает секрет в JSON")
    fun invalidRegistration() {
        val b = browser()
        for (fields in listOf(emptyMap(), mapOf("username" to "xx", "password" to password), mapOf("username" to name(), "password" to "short"))) {
            val result = b.post(REGISTER, fields + ("_csrf" to b.csrf()))
            assertEquals(400, result.statusCode())
            assertFalse(result.body().contains(password))
            assertFalse(result.body().contains("short"))
        }
    }

    @Test
    @DisplayName("Клиентские роль и UUID игнорируются, повторная регистрация не меняет пароль")
    fun privilegeAndDuplicate() {
        val b = browser(); val username = name()
        assertEquals(201, b.post(REGISTER, mapOf("username" to username, "password" to password, "role" to "ADMIN", "enabled" to "true", "id" to "forged", "_csrf" to b.csrf())).statusCode())
        assertEquals("USER", accounts.findByUsername(username)!!.role)
        val duplicate = b.post(REGISTER, mapOf("username" to username.uppercase(), "password" to "another passphrase", "_csrf" to b.csrf()))
        assertEquals(409, duplicate.statusCode())
        assertTrue(encoder.matches(password, accounts.findByUsername(username)!!.passwordHash))
    }

    @Test
    @DisplayName("Конкурентная регистрация даёт один успех и один конфликт уникальности PostgreSQL")
    fun concurrentRegistration() {
        val username = name(); val barrier = CyclicBarrier(2); val executor = Executors.newFixedThreadPool(2)
        try {
            val futures = (1..2).map {
                executor.submit(Callable {
                    val b = browser(); val csrf = b.csrf(); barrier.await(5, TimeUnit.SECONDS)
                    b.post(REGISTER, mapOf("username" to username, "password" to password, "_csrf" to csrf)).statusCode()
                })
            }
            assertEquals(listOf(201, 409), futures.map { it.get(15, TimeUnit.SECONDS) }.sorted())
            assertEquals(1, jdbc.queryForObject("select count(*) from app_users where username = ?", Int::class.java, username))
        } finally { executor.shutdownNow() }
    }

    @Test
    @DisplayName("Allowlist закрывает неизвестные пути, публичный React shell не содержит identity")
    fun defaultDenial() {
        val b = browser()
        assertEquals(200, b.get("/").statusCode())
        assertEquals(200, b.get(APP).statusCode())
        assertEquals(401, b.get(LOGOUT).statusCode())
        assertEquals(401, b.get(ME).statusCode())
        assertEquals(401, b.get(UNLISTED_API).statusCode())
        assertEquals(401, b.get("/error").statusCode())
        val username = register(b); login(b, username)
        for (path in listOf("/private", "/error", UNLISTED_API)) assertEquals(403, b.get(path).statusCode())
        assertEquals(403, b.post(UNLISTED_API, mapOf("_csrf" to b.csrf())).statusCode())
        mvc.perform(post(UNLISTED_API).with(csrf().useInvalidToken())).andExpect(status().isForbidden).andExpect(jsonPath("$.code").value("FORBIDDEN"))
    }

    @Test
    @DisplayName("Персональные ответы не кэшируются, CSP запрещает framing, CORS не разрешает чужой origin")
    fun browserHeaders() {
        val b = browser(); val username = register(b); login(b, username)
        for (path in listOf(APP, ME)) assertTrue(b.get(path).headers().firstValue("cache-control").get().contains("no-store"))
        val page = b.get(APP)
        assertTrue(page.headers().firstValue("content-security-policy").get().contains("frame-ancestors 'none'"))
        assertEquals("DENY", page.headers().firstValue("x-frame-options").get())
        assertEquals("nosniff", page.headers().firstValue("x-content-type-options").get())
        mvc.perform(get("/login").header("Origin", "https://untrusted.example")).andExpect(header().doesNotExist("Access-Control-Allow-Origin"))
        val fresh = browser().get("/api/v1/auth/csrf").headers().allValues(HttpHeaders.SET_COOKIE).single()
        assertTrue(fresh.contains("HttpOnly")); assertTrue(fresh.contains("SameSite=Lax")); assertTrue(fresh.contains("Path=/"))
        assertFalse(fresh.contains("Secure")); assertFalse(fresh.contains("Domain="))
    }

    @Test
    @DisplayName("Сессия не принимается через URL rewriting")
    fun noUrlSession() {
        val b = browser(); login(b, register(b))
        assertEquals(400, browser().get("/api/v1/me;jsessionid=" + b.sessionId()).statusCode())
    }

    @Test
    @DisplayName("Flyway применён, PostgreSQL запрещает неверную роль и ненормализованный логин")
    fun databaseConstraints() {
        assertEquals(3, jdbc.queryForObject("select count(*) from flyway_schema_history where success", Int::class.java))
        for ((username, role) in listOf("Alice" to "USER", name() to "ADMIN")) {
            assertThrows(org.springframework.dao.DataIntegrityViolationException::class.java) {
                jdbc.update("insert into app_users values (?, ?, ?, ?, now())", UUID.randomUUID(), username, "test-hash", role)
            }
        }
    }

    @Test
    @DisplayName("OpenAPI описывает фактические поля и статусы текущего пользователя")
    fun openApiContract() {
        val yaml = java.nio.file.Files.readString(java.nio.file.Path.of("../docs/openapi.yaml"))
        val document = org.yaml.snakeyaml.Yaml().load<Map<String, Any>>(yaml)
        val components = document["components"] as Map<*, *>
        val schemas = components["schemas"] as Map<*, *>
        val mapper = tools.jackson.module.kotlin.jacksonObjectMapper()
        val b = browser()
        fun checkSchema(body: String, schemaName: String) {
            val schema = schemas[schemaName] as Map<*, *>
            val node = mapper.readTree(body)
            assertEquals((schema["required"] as List<*>).toSet(), node.propertyNames().toSet())
            assertEquals(false, schema["additionalProperties"])
            val properties = schema["properties"] as Map<*, *>
            for ((key, raw) in properties) {
                val property = raw as Map<*, *>
                val value = node[key.toString()]
                when (property["type"]) {
                    "string" -> {
                        assertTrue(value.isString)
                        if (property["format"] == "uuid") assertNotNull(UUID.fromString(value.asString()))
                        (property["pattern"] as? String)?.let { assertTrue(Regex(it).matches(value.asString())) }
                        (property["enum"] as? List<*>)?.let { assertTrue(it.contains(value.asString())) }
                    }
                    "array" -> {
                        assertEquals(1, value.size())
                        assertEquals("USER", value[0].asString())
                    }
                }
            }
        }
        val unauthenticated = b.get(ME)
        assertEquals(401, unauthenticated.statusCode())
        checkSchema(unauthenticated.body(), "Unauthenticated")
        login(b, register(b))
        val authenticated = b.get(ME)
        assertEquals(200, authenticated.statusCode())
        checkSchema(authenticated.body(), "CurrentIdentity")
        val paths = document["paths"] as Map<*, *>
        val operation = (paths[ME] as Map<*, *>)["get"] as Map<*, *>
        val responses = operation["responses"] as Map<*, *>
        assertEquals(setOf("200", "401", "500"), responses.keys)
        assertTrue(authenticated.headers().firstValue("content-type").get().startsWith("application/json"))
    }

    @Test
    @DisplayName("Реальные security requests возвращают requestId и не оставляют credentials/query в логах")
    fun logContract(output: org.springframework.boot.test.system.CapturedOutput) {
        val b = browser()
        val username = register(b)
        val duplicate = b.post(REGISTER, mapOf("username" to username, "password" to password, "_csrf" to b.csrf()))
        assertEquals(409, duplicate.statusCode())
        assertEquals(200, login(b, username).statusCode())
        val secret = "sentinel_private_query_" + UUID.randomUUID()
        val rejection = b.get(UNLISTED_API + "?token=" + secret)
        assertEquals(403, rejection.statusCode())
        val id = rejection.headers().firstValue("X-Request-ID").orElseThrow()
        assertNotNull(UUID.fromString(id))
        assertTrue(output.all.contains(id))
        assertTrue(output.all.contains("auth.login.succeeded"))
        assertTrue(output.all.contains("account.registration.rejected"))
        assertFalse(output.all.contains(username))
        assertFalse(output.all.contains(password))
        assertFalse(output.all.contains(secret))
    }

    companion object {
        @JvmStatic @DynamicPropertySource
        fun database(registry: DynamicPropertyRegistry) = TestDatabase.properties(registry)
    }
}
