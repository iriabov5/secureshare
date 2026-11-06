package dev.secureshare

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.AppenderBase
import dev.secureshare.files.FileUnavailableException
import dev.secureshare.observability.*
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.web.servlet.HandlerMapping
import java.io.IOException
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class CapturedEvents : AppenderBase<ILoggingEvent>() {
    val events = CopyOnWriteArrayList<ILoggingEvent>()
    override fun append(event: ILoggingEvent) {
        event.prepareForDeferredProcessing()
        events.add(event)
    }
}

class ObservabilityTest {
    private val logger = LoggerFactory.getLogger("dev.secureshare.operations") as Logger
    private val capture = CapturedEvents()
    private var previousLevel: ch.qos.logback.classic.Level? = null
    private val filter = RequestLoggingFilter()

    @BeforeEach
    fun attach() {
        previousLevel = logger.level
        logger.level = ch.qos.logback.classic.Level.DEBUG
        capture.context = logger.loggerContext
        capture.start()
        logger.addAppender(capture)
    }

    @AfterEach
    fun detach() {
        logger.detachAppender(capture)
        logger.level = previousLevel
        capture.stop()
        MDC.clear()
    }

    private fun fields(event: ILoggingEvent) = event.keyValuePairs.associate { it.key to it.value }
    private fun outcomes() = capture.events.filter { fields(it)["event"] == "http.request.completed" }

    @Test
    @DisplayName("RequestId генерируется сервером, связывает события и восстанавливает внешний MDC")
    fun correlation() {
        MDC.put("outer", "before")
        val request = MockHttpServletRequest("POST", "/api/v1/auth/login").apply { addHeader("X-Request-ID", "untrusted-input") }
        val response = MockHttpServletResponse()
        filter.doFilter(request, response) { _, _ ->
            assertNull(MDC.get("outer"))
            assertEquals(request.getAttribute(RequestLoggingFilter.REQUEST_ID), MDC.get("requestId"))
            AppLog.event("auth.login.succeeded", Outcome.SUCCESS, fields = mapOf("accountId" to UUID.randomUUID()))
        }
        val id = response.getHeader("X-Request-ID")!!
        assertNotNull(UUID.fromString(id))
        assertNotEquals("untrusted-input", id)
        assertTrue(capture.events.all { it.mdcPropertyMap["requestId"] == id })
        assertEquals("before", MDC.get("outer"))
        assertNull(MDC.get("requestId"))
        assertEquals(1, outcomes().size)
        assertEquals("SUCCESS", fields(outcomes().single())["outcome"])
        assertEquals(200, fields(outcomes().single())["status"])
    }

    @Test
    @DisplayName("Логи не содержат заголовки, query, сырой маршрут или сообщения исключений")
    fun untrustedInputAndError() {
        val secret = "sentinel-private-password"
        val request = MockHttpServletRequest("SECRET_METHOD", "/$secret").apply {
            queryString = "token=$secret"
            addHeader("Authorization", "Bearer $secret")
            addHeader("X-Request-ID", "$secret\r\nforged-entry")
            setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, "/$secret")
        }
        val response = MockHttpServletResponse()
        filter.doFilter(request, response) { _, _ -> throw IllegalStateException(secret, IOException(secret)) }
        assertTrue(response.contentAsString.contains("INTERNAL_ERROR"))
        assertFalse(response.contentAsString.contains(secret))
        val completion = fields(outcomes().single())
        assertEquals(500, completion["status"])
        assertEquals("ERROR", completion["outcome"])
        assertEquals("UNMATCHED", completion["route"])
        assertEquals("OTHER", completion["method"])
        assertTrue(capture.events.any { fields(it)["rootCauseType"] == IOException::class.java.name })
        capture.events.forEach {
            assertFalse((it.formattedMessage + fields(it) + it.mdcPropertyMap).contains(secret))
            assertNull(it.throwableProxy)
        }
    }

    @Test
    @DisplayName("HTTP коды, статические маршруты и client disconnect имеют однозначный уровень и исход")
    fun statusesAndRoutes() {
        for ((path, status) in listOf("/api/v1/files" to 201, "/api/v1/me" to 401, "/api/v1/me" to 503, "/assets/private.js" to 200, "/favicon.svg" to 200, "/api/v1/auth/csrf" to 200)) {
            val response = MockHttpServletResponse()
            filter.doFilter(MockHttpServletRequest("GET", path), response) { _, _ -> response.status = status }
            val event = outcomes().last()
            assertEquals(status, fields(event)["status"])
            assertEquals(when { status >= 500 -> "ERROR"; status >= 400 -> "WARN"; path.startsWith("/assets/") || path.endsWith("svg") || path.endsWith("csrf") -> "DEBUG"; else -> "INFO" }, event.level.toString())
            assertTrue((fields(event)["durationMs"] as Long) >= 0)
        }
        val response = MockHttpServletResponse()
        filter.doFilter(MockHttpServletRequest("GET", "/api/v1/files/${UUID.randomUUID()}/content"), response) { _, _ ->
            response.flushBuffer()
            throw IOException("untrusted transport message")
        }
        assertEquals("CLIENT_DISCONNECTED", fields(outcomes().last())["outcome"])
        assertEquals("/api/v1/files/{id}/content", fields(outcomes().last())["route"])
    }

    @Test
    @DisplayName("Forward и error dispatch не дублируют completion, безопасный MVC шаблон сохраняется")
    fun dispatches() {
        val request = MockHttpServletRequest("GET", "/files")
        val response = MockHttpServletResponse()
        filter.doFilter(request, response) { _, _ ->
            request.dispatcherType = jakarta.servlet.DispatcherType.FORWARD
            filter.doFilter(request, response) { _, _ ->
                request.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, "/files")
            }
        }
        request.dispatcherType = jakarta.servlet.DispatcherType.ERROR
        request.setAttribute("jakarta.servlet.error.request_uri", "/files")
        filter.doFilter(request, response) { _, _ -> }
        assertEquals(1, outcomes().size)
        assertEquals("/files", fields(outcomes().single())["route"])
    }

    @Test
    @DisplayName("Конкурентные запросы и повторное использование потока не смешивают MDC")
    fun concurrentRequests() {
        val executor = Executors.newFixedThreadPool(2)
        val barrier = CyclicBarrier(2)
        try {
            val ids = (1..2).map {
                executor.submit<String> {
                    val response = MockHttpServletResponse()
                    filter.doFilter(MockHttpServletRequest("GET", "/api/v1/me"), response) { _, _ ->
                        barrier.await(5, TimeUnit.SECONDS)
                        AppLog.event("concurrent.probe", Outcome.SUCCESS)
                    }
                    assertNull(MDC.get("requestId"))
                    response.getHeader("X-Request-ID")!!
                }
            }.map { it.get(10, TimeUnit.SECONDS) }
            assertEquals(2, ids.toSet().size)
            ids.forEach { id ->
                val events = capture.events.filter { it.mdcPropertyMap["requestId"] == id }
                assertEquals(1, events.count { fields(it)["event"] == "http.request.completed" })
                assertEquals(1, events.count { fields(it)["event"] == "concurrent.probe" })
            }
        } finally { executor.shutdownNow() }
    }

    @Test
    @DisplayName("Причины DB/S3 ошибок диагностируются без сообщений, неизвестный commit не выдаётся за успех")
    fun safeFailures() {
        val id = UUID.randomUUID()
        for (stage in Stage.entries) {
            val exception = FileUnavailableException(IOException("password=sentinel-secret; SQL private input"), stage, Outcome.UNKNOWN, id)
            exception.addSuppressed(IllegalStateException("sentinel-secret"))
            AppLog.failure(Operation.FILE_UPLOAD, exception)
            val event = capture.events.last()
            val values = fields(event)
            assertEquals("UNKNOWN", values["outcome"])
            assertEquals(stage.name, values["stage"])
            assertEquals(id, values["fileId"])
            assertEquals("FAILED", values["cleanupOutcome"])
            assertEquals("ERROR", event.level.toString())
            assertFalse(values.toString().contains("sentinel-secret"))
            assertNull(event.throwableProxy)
        }
        AppLog.failure(Operation.REGISTRATION, IllegalStateException("private message"), Stage.REGISTRATION_COMMIT, id)
        assertEquals("ERROR", fields(capture.events.last())["outcome"])
        assertEquals("POSTGRES", fields(capture.events.last())["dependency"])
    }

    @Test
    @DisplayName("Скачивание подтверждается только после выдачи response, а не при подготовке байтов")
    fun completedDownload() {
        val details = mapOf<String, Any>("fileId" to UUID.randomUUID(), "accountId" to UUID.randomUUID(), "sizeBytes" to 15)
        for (status in listOf(200, 503)) {
            val request = MockHttpServletRequest("GET", "/api/v1/files/${UUID.randomUUID()}/content")
            request.setAttribute(RequestLoggingFilter.DOWNLOAD, details)
            val response = MockHttpServletResponse()
            filter.doFilter(request, response) { _, _ -> response.status = status }
        }
        assertEquals(1, capture.events.count { fields(it)["event"] == "file.download.succeeded" })
        assertEquals(2, outcomes().size)
    }
    @Test
    @DisplayName("Reason codes различают timeout, connection, SQL state и S3 status без исходного текста")
    fun reasonCodes() {
        val cases = listOf(
            java.net.SocketTimeoutException("private") to "DEPENDENCY_TIMEOUT",
            java.util.concurrent.TimeoutException("private") to "DEPENDENCY_TIMEOUT",
            software.amazon.awssdk.core.exception.ApiCallTimeoutException.create(10) to "DEPENDENCY_TIMEOUT",
            software.amazon.awssdk.core.exception.ApiCallAttemptTimeoutException.create(10) to "DEPENDENCY_TIMEOUT",
            java.net.ConnectException("private") to "DEPENDENCY_CONNECTION_FAILED",
            java.sql.SQLException("private", "08001") to "DEPENDENCY_CONNECTION_FAILED",
            java.sql.SQLException("private", "28000") to "DEPENDENCY_AUTHENTICATION_FAILED",
            java.sql.SQLException("private", "23505") to "DATA_CONSTRAINT_FAILED",
            java.sql.SQLException("private", "99999") to "DEPENDENCY_FAILURE",
            java.sql.SQLException("private") to "DEPENDENCY_FAILURE",
            software.amazon.awssdk.services.s3.model.S3Exception.builder().statusCode(403).message("private").build() to "STORAGE_ACCESS_DENIED",
            software.amazon.awssdk.services.s3.model.S3Exception.builder().statusCode(404).message("private").build() to "STORAGE_OBJECT_MISSING",
            software.amazon.awssdk.services.s3.model.S3Exception.builder().statusCode(503).message("private").build() to "DEPENDENCY_FAILURE",
            FileUnavailableException(stage = Stage.METADATA_READY, outcome = Outcome.UNKNOWN) to "COMMIT_UNCONFIRMED",
        )
        cases.forEach { (error, expected) ->
            AppLog.failure(Operation.FILE_UPLOAD, error)
            assertEquals(expected, fields(capture.events.last())["reasonCode"])
            assertFalse(fields(capture.events.last()).toString().contains("private"))
        }
    }

}
