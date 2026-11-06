package dev.secureshare

import dev.secureshare.TestEndpoints.REGISTER
import dev.secureshare.TestEndpoints.LOGIN
import dev.secureshare.TestEndpoints.FILES_PAGE
import dev.secureshare.TestEndpoints.FILES_API

import tools.jackson.module.kotlin.jacksonObjectMapper
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

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("local")
class FileDependencyOutageTest {
    @LocalServerPort private var port = 0
    private val mapper = jacksonObjectMapper()
    private fun account(): Browser {
        val b = Browser("http://localhost:$port"); val name = "u" + UUID.randomUUID().toString().replace("-", ""); val pass = "Outage file passphrase"
        assertEquals(201, b.post(REGISTER, mapOf("username" to name, "password" to pass, "_csrf" to b.csrf())).statusCode())
        assertEquals(200, b.post(LOGIN, mapOf("username" to name, "password" to pass, "_csrf" to b.csrf())).statusCode())
        return b
    }

    @Test
    @DisplayName("При отказе S3 нет ложного upload/download, чужой запрос отклоняется до обращения к S3")
    fun objectStorageOutage() {
        val owner = account(); val other = account()
        val response = owner.multipart(FILES_API, "ok.txt", "private content".toByteArray(), owner.csrf())
        assertEquals(201, response.statusCode())
        val id = UUID.fromString(mapper.readTree(response.body())["id"].asString())
        val csrf = owner.csrf()
        val docker = org.testcontainers.DockerClientFactory.instance().client()
        docker.pauseContainerCmd(store.container.containerId).exec()
        try {
            assertEquals(200, owner.get("/api/v1/me/sessions").statusCode())
            assertEquals(403, other.get(TestEndpoints.fileContent(id)).statusCode())
            assertEquals(503, owner.get(TestEndpoints.fileContent(id)).statusCode())
            val failed = owner.multipart(FILES_API, "failed.txt", "not ready".toByteArray(), csrf)
            assertEquals(503, failed.statusCode())
            assertFalse(failed.body().contains("AWS")); assertFalse(failed.body().contains(store.secret))
            assertEquals(1, mapper.readTree(owner.get(FILES_API).body()).size())
        } finally { docker.unpauseContainerCmd(store.container.containerId).exec() }
        assertEquals(200, owner.get(TestEndpoints.fileContent(id)).statusCode())
    }

    @Test
    @DisplayName("При отказе PostgreSQL file API возвращает безопасный 503 и после восстановления продолжает работать")
    fun metadataOutage() {
        val owner = account()
        val response = owner.multipart(FILES_API, "ok.txt", "private".toByteArray(), owner.csrf())
        assertEquals(201, response.statusCode())
        val id = UUID.fromString(mapper.readTree(response.body())["id"].asString()); val csrf = owner.csrf()
        val docker = org.testcontainers.DockerClientFactory.instance().client()
        docker.pauseContainerCmd(postgres.containerId).exec()
        try {
            for (path in listOf(FILES_API, TestEndpoints.fileContent(id))) {
                val failed = owner.get(path)
                assertEquals(503, failed.statusCode())
                assertEquals("FILE_SERVICE_UNAVAILABLE", mapper.readTree(failed.body())["code"].asString())
            }
            assertEquals(503, owner.multipart(FILES_API, "failed.txt", "fail".toByteArray(), csrf).statusCode())
        } finally { docker.unpauseContainerCmd(postgres.containerId).exec() }
        assertEquals(200, owner.get(TestEndpoints.fileContent(id)).statusCode())
    }

    companion object {
        private val store = TestS3Store()
        private val postgres = PostgreSQLContainer(org.testcontainers.utility.DockerImageName.parse("postgres:17.6-alpine")).apply { start() }
        @JvmStatic @DynamicPropertySource
        fun properties(registry: DynamicPropertyRegistry) {
            store.properties(registry)
            registry.add("spring.datasource.url") { postgres.jdbcUrl + "&socketTimeout=2&connectTimeout=2" }
            registry.add("spring.datasource.username", postgres::getUsername)
            registry.add("spring.datasource.password", postgres::getPassword)
        }
    }
}
