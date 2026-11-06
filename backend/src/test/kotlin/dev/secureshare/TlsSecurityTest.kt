package dev.secureshare

import org.springframework.http.HttpHeaders

import dev.secureshare.TestEndpoints.REGISTER
import dev.secureshare.TestEndpoints.LOGIN
import dev.secureshare.TestEndpoints.LOGOUT
import dev.secureshare.TestEndpoints.ME
import dev.secureshare.TestEndpoints.APP

import jakarta.servlet.http.HttpSession
import jakarta.servlet.http.HttpSessionEvent
import jakarta.servlet.http.HttpSessionListener
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.boot.web.servlet.ServletListenerRegistrationBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyStore
import java.time.Duration
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory

class SessionProbe : HttpSessionListener {
    val sessions = ConcurrentHashMap<String, HttpSession>()
    override fun sessionCreated(event: HttpSessionEvent) { sessions[event.session.id] = event.session }
}

/** Генерирует локальный TLS-сертификат и доверяет только ему. */
object TestTls {
    private const val password = "local-test-certificate"
    val path: Path = Path.of("build/test-tls.p12").toAbsolutePath()
    init {
        Files.deleteIfExists(path)
        val process = ProcessBuilder(Path.of(System.getProperty("java.home"), "bin/keytool").toString(),
            "-genkeypair", "-alias", "test", "-keyalg", "RSA", "-keysize", "2048", "-storetype", "PKCS12",
            "-keystore", path.toString(), "-storepass", password, "-dname", "CN=localhost", "-validity", "2",
            "-ext", "SAN=DNS:localhost,IP:127.0.0.1").redirectErrorStream(true).start()
        process.inputStream.readAllBytes()
        check(process.waitFor() == 0) { "Cannot generate test TLS certificate" }
    }
    fun properties(registry: DynamicPropertyRegistry) {
        registry.add("server.ssl.enabled") { true }
        registry.add("server.ssl.key-store") { path.toUri().toString() }
        registry.add("server.ssl.key-store-password") { password }
    }
    /** Не отключает валидацию сертификата или hostname. */
    fun context(): SSLContext {
        val store = KeyStore.getInstance("PKCS12").apply { Files.newInputStream(path).use { load(it, password.toCharArray()) } }
        val managers = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(store) }
        return SSLContext.getInstance("TLSv1.3").apply { init(null, managers.trustManagers, null) }
    }
}

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TlsSecurityTest.ProbeConfiguration::class)
class TlsSecurityTest {
    @LocalServerPort private var port = 0
    @org.springframework.beans.factory.annotation.Autowired private lateinit var probe: SessionProbe
    @org.springframework.beans.factory.annotation.Autowired private lateinit var environment: org.springframework.core.env.Environment

    @Test
    @DisplayName("HTTPS cookie имеет Secure/HttpOnly/SameSite, а контейнер действительно отзывает истёкшую сессию")
    fun secureCookiesAndExpiry() {
        val b = Browser("https://localhost:$port", TestTls.context())
        val page = b.get("/api/v1/auth/csrf")
        val cookie = page.headers().allValues(HttpHeaders.SET_COOKIE).single()
        for (part in listOf("Secure", "HttpOnly", "SameSite=Lax", "Path=/")) assertTrue(cookie.contains(part), part)
        assertFalse(cookie.contains("Domain="))
        assertTrue(page.headers().firstValue("strict-transport-security").isPresent)
        assertEquals("30m", environment.getProperty("server.servlet.session.timeout"))
        val username = "u" + UUID.randomUUID().toString().replace("-", "")
        val password = "Correct TLS passphrase"
        assertEquals(201, b.post(REGISTER, mapOf("username" to username, "password" to password, "_csrf" to tools.jackson.module.kotlin.jacksonObjectMapper().readTree(page.body())["token"].asString())).statusCode())
        assertEquals(200, b.post(LOGIN, mapOf("username" to username, "password" to password, "_csrf" to b.csrf())).statusCode())
        assertEquals(200, b.get(ME).statusCode())
        val session = probe.sessions.values.first { it.id == b.sessionId() }
        // Уменьшаем только тестовую сессию; штатный timeout проверен выше.
        session.maxInactiveInterval = 1
        val lastAccess = session.lastAccessedTime
        await().atMost(Duration.ofSeconds(4)).until { System.currentTimeMillis() - lastAccess > 1200 }
        assertEquals(401, b.get(ME).statusCode())
        assertEquals(200, b.get(APP).statusCode())
        // Повторно входим и проверяем Secure cookie удаления именно в HTTPS.
        assertEquals(200, b.post(LOGIN, mapOf("username" to username, "password" to password, "_csrf" to b.csrf())).statusCode())
        val logout = b.post(LOGOUT, mapOf("_csrf" to b.csrf()))
        assertTrue(logout.headers().allValues(HttpHeaders.SET_COOKIE).flatMap(java.net.HttpCookie::parse).any {
            it.name == "JSESSIONID" && it.maxAge == 0L && it.secure && it.path == "/"
        })
    }

    @TestConfiguration(proxyBeanMethods = false)
    class ProbeConfiguration {
        @Bean fun probe() = SessionProbe()
        @Bean fun sessionListener(probe: SessionProbe) = ServletListenerRegistrationBean(probe)
    }

    companion object {
        @JvmStatic @DynamicPropertySource
        fun properties(registry: DynamicPropertyRegistry) { TestDatabase.properties(registry); TestTls.properties(registry) }
    }
}
