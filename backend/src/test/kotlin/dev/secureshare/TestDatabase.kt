package dev.secureshare

import org.springframework.test.context.DynamicPropertyRegistry
import org.testcontainers.postgresql.PostgreSQLContainer

/** Запускает настоящую PostgreSQL; отсутствие Docker приводит к ошибке тестов. */
object TestDatabase {
    val postgres = PostgreSQLContainer(org.testcontainers.utility.DockerImageName.parse("postgres:17.6-alpine")).apply { start() }

    fun properties(registry: DynamicPropertyRegistry) {
        TestFileKeys.properties(registry)
        registry.add("spring.datasource.url", postgres::getJdbcUrl)
        registry.add("spring.datasource.username", postgres::getUsername)
        registry.add("spring.datasource.password", postgres::getPassword)
    }
}
