package dev.secureshare

import dev.secureshare.TestEndpoints.REGISTER
import dev.secureshare.TestEndpoints.LOGIN
import dev.secureshare.TestEndpoints.ME

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.postgresql.PostgreSQLContainer
import java.util.UUID

/** Отказы проверяются на отдельной PostgreSQL, не затрагивая соседние тесты. */
@org.junit.jupiter.api.extension.ExtendWith(org.springframework.boot.test.system.OutputCaptureExtension::class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("local")
class DatabaseOutageTest {
    @LocalServerPort private var port = 0

    @Test
    @DisplayName("Отказ БД даёт 503 без частичной регистрации и отзывает повторный login; существующая сессия читает identity")
    fun outage(output: org.springframework.boot.test.system.CapturedOutput) {
        val origin = "http://localhost:$port"
        val b = Browser(origin)
        val user = "u" + UUID.randomUUID().toString().replace("-", "")
        val password = "Correct outage passphrase"
        assertEquals(201, b.post(REGISTER, mapOf("username" to user, "password" to password, "_csrf" to b.csrf())).statusCode())
        assertEquals(200, b.post(LOGIN, mapOf("username" to user, "password" to password, "_csrf" to b.csrf())).statusCode())
        val registration = Browser(origin); val csrf = registration.csrf()
        val absent = user + "x"
        // Замораживаем отдельный контейнер: TCP недоступен, mapped port и данные сохраняются.
        val docker = org.testcontainers.DockerClientFactory.instance().client()
        docker.pauseContainerCmd(postgres.containerId).exec()
        try {
            assertEquals(200, b.get(ME).statusCode())
            val failedRegistration = registration.post(REGISTER, mapOf("username" to absent, "password" to password, "_csrf" to csrf))
            assertEquals(503, failedRegistration.statusCode())
            assertFalse(failedRegistration.body().contains(password))
            assertFalse(failedRegistration.body().contains("Exception"))
            assertEquals(401, registration.get(ME).statusCode())
            val failedLogin = b.post(LOGIN, mapOf("username" to user, "password" to password, "_csrf" to b.csrf()))
            assertEquals(503, failedLogin.statusCode())
            assertFalse(failedLogin.body().contains(password))
            assertEquals(401, b.get(ME).statusCode())
            assertFalse(output.all.contains(password))
        } finally {
            docker.unpauseContainerCmd(postgres.containerId).exec()
        }
        assertEquals(201, registration.post(REGISTER, mapOf("username" to absent, "password" to password, "_csrf" to registration.csrf())).statusCode())
    }

    companion object {
        private val postgres = PostgreSQLContainer(org.testcontainers.utility.DockerImageName.parse("postgres:17.6-alpine")).apply { start() }
        @JvmStatic @DynamicPropertySource
        fun database(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url") { postgres.jdbcUrl + "&socketTimeout=2&connectTimeout=2" }
            registry.add("spring.datasource.username", postgres::getUsername)
            registry.add("spring.datasource.password", postgres::getPassword)
        }
    }
}
