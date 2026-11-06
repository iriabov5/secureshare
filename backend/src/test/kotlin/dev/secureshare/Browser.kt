package dev.secureshare

import dev.secureshare.TestEndpoints.ME

import java.net.CookieManager
import java.net.CookiePolicy
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration
import javax.net.ssl.SSLContext

/** Настоящий браузерный HTTP-клиент с cookies, без автоматических redirects. */
class Browser(private val origin: String, ssl: SSLContext? = null) {
    private val cookies = CookieManager(null, CookiePolicy.ACCEPT_ALL)
    private val client = HttpClient.newBuilder().cookieHandler(cookies)
        .followRedirects(HttpClient.Redirect.NEVER).connectTimeout(Duration.ofSeconds(5))
        .apply { if (ssl != null) sslContext(ssl) }.build()

    fun get(path: String): HttpResponse<String> = client.send(
        HttpRequest.newBuilder(URI.create(origin + path)).timeout(Duration.ofSeconds(15)).GET().build(),
        HttpResponse.BodyHandlers.ofString(),
    )

    fun post(path: String, fields: Map<String, String>): HttpResponse<String> {
        val payload = fields.filterKeys { it != "_csrf" }
        val registration = path.substringBefore('?') == TestEndpoints.REGISTER
        val body = if (registration) tools.jackson.module.kotlin.jacksonObjectMapper().writeValueAsString(payload)
            else payload.entries.joinToString("&") { (key, value) -> encode(key) + "=" + encode(value) }
        return client.send(HttpRequest.newBuilder(URI.create(origin + path)).timeout(Duration.ofSeconds(15))
            .header("Content-Type", if (registration) "application/json" else "application/x-www-form-urlencoded")
            .apply { fields["_csrf"]?.let { header("X-CSRF-TOKEN", it) } }
            .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString())
    }

    fun csrf(): String = tools.jackson.module.kotlin.jacksonObjectMapper().readTree(get("/api/v1/auth/csrf").body())["token"].asString()

    fun sessionId(): String = cookies.cookieStore.cookies.first { it.name == "JSESSIONID" }.value.removeSurrounding("\"")

    fun replay(id: String): Int = HttpClient.newHttpClient().send(
        HttpRequest.newBuilder(URI.create(origin + ME)).header("Cookie", "JSESSIONID=$id").GET().build(),
        HttpResponse.BodyHandlers.discarding(),
    ).statusCode()

    fun multipart(path: String, filename: String, bytes: ByteArray, csrf: String?, extras: Map<String, String> = emptyMap()): HttpResponse<String> {
        val boundary = "SecureShareBoundary" + java.util.UUID.randomUUID()
        val body = java.io.ByteArrayOutputStream()
        fun text(value: String) { body.write(value.toByteArray(StandardCharsets.UTF_8)) }
        extras.forEach { (key, value) ->
            text("--$boundary\r\nContent-Disposition: form-data; name=\"$key\"\r\n\r\n$value\r\n")
        }
        text("--$boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"$filename\"\r\nContent-Type: application/octet-stream\r\n\r\n")
        body.write(bytes)
        text("\r\n--$boundary--\r\n")
        val request = HttpRequest.newBuilder(URI.create(origin + path)).timeout(Duration.ofSeconds(20))
            .header("Content-Type", "multipart/form-data; boundary=$boundary")
            .apply { if (csrf != null) header("X-CSRF-TOKEN", csrf) }
            .POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray())).build()
        return client.send(request, HttpResponse.BodyHandlers.ofString())
    }

    fun bytes(path: String): HttpResponse<ByteArray> = client.send(
        HttpRequest.newBuilder(URI.create(origin + path)).timeout(Duration.ofSeconds(15)).GET().build(),
        HttpResponse.BodyHandlers.ofByteArray(),
    )

    private fun encode(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8)

}
