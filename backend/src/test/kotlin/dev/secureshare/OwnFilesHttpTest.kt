package dev.secureshare

import org.springframework.http.MediaType

import dev.secureshare.TestEndpoints.REGISTER
import dev.secureshare.TestEndpoints.LOGIN
import dev.secureshare.TestEndpoints.ME
import dev.secureshare.TestEndpoints.FILES_PAGE
import dev.secureshare.TestEndpoints.FILES_API

import tools.jackson.module.kotlin.jacksonObjectMapper
import dev.secureshare.files.*
import dev.secureshare.security.AccountPrincipal
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.access.AccessDeniedException
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.*
import software.amazon.awssdk.services.s3.S3Client
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.UUID

private const val REDACTED = "<redacted>"
private const val VALID_FILE_NAME = "ok.txt"

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@ActiveProfiles("local")
class OwnFilesHttpTest {
    @LocalServerPort private var port = 0
    @Autowired private lateinit var jdbc: JdbcTemplate
    @Autowired private lateinit var catalog: FileCatalog
    @Autowired private lateinit var service: FileService
    @Autowired private lateinit var s3: S3Client
    @Autowired private lateinit var mvc: MockMvc
    private val mapper = jacksonObjectMapper()
    private fun browser() = Browser("http://localhost:$port")
    private fun account(): Pair<Browser, UUID> {
        val b = browser(); val name = "u" + UUID.randomUUID().toString().replace("-", ""); val pass = "File test passphrase"
        assertEquals(201, b.post(REGISTER, mapOf("username" to name, "password" to pass, "_csrf" to b.csrf())).statusCode())
        assertEquals(200, b.post(LOGIN, mapOf("username" to name, "password" to pass, "_csrf" to b.csrf())).statusCode())
        return b to UUID.fromString(mapper.readTree(b.get(ME).body())["id"].asString())
    }
    private fun upload(b: Browser, bytes: ByteArray = "private file".toByteArray(), name: String = "report.txt"): UUID {
        val response = b.multipart(FILES_API, name, bytes, b.csrf())
        if (response.statusCode() != 201) {
            val log = TestObjectStore.container.logs.replace(TestObjectStore.secret, REDACTED).replace(TestObjectStore.access, REDACTED)
            java.nio.file.Files.writeString(java.nio.file.Path.of("build/s3-upload-failure.log"), log)
        }
        assertEquals(201, response.statusCode(), response.body())
        val info = mapper.readTree(response.body())
        assertEquals(setOf("id", "name", "sizeBytes", "sha256", "createdAt"), info.propertyNames().toSet())
        assertEquals(FilePolicy.sha256(bytes), info["sha256"].asString())
        return UUID.fromString(info["id"].asString())
    }

    @Test
    @DisplayName("Владелец загружает файл в S3 и скачивает исходные байты; другой пользователь не видит файл")
    fun ownerIsolation() {
        val (owner, ownerId) = account(); val (other, _) = account()
        val bytes = byteArrayOf(0, 1, 2, 3, -1)
        val id = upload(owner, bytes)
        val info = mapper.readTree(owner.get(FILES_API).body())
        assertEquals(1, info.size()); assertEquals(id.toString(), info[0]["id"].asString())
        assertEquals("[]", other.get(FILES_API).body())
        assertEquals(ownerId, jdbc.queryForObject("select owner_id from stored_files where id=?", UUID::class.java, id))
        assertArrayEquals(bytes, owner.bytes(TestEndpoints.fileContent(id)).body())
        val download = owner.bytes(TestEndpoints.fileContent(id))
        assertEquals(200, download.statusCode())
        assertEquals(MediaType.APPLICATION_OCTET_STREAM_VALUE, download.headers().firstValue("content-type").get())
        assertTrue(download.headers().firstValue("content-disposition").get().startsWith("attachment;"))
        assertTrue(download.headers().firstValue("cache-control").get().contains("no-store"))
        assertEquals(403, other.get(TestEndpoints.fileContent(id)).statusCode())
        assertEquals(403, other.get(TestEndpoints.fileContent(UUID.randomUUID())).statusCode())
        assertEquals(400, owner.get("/api/v1/files/not-a-uuid/content").statusCode())
    }

    @Test
    @DisplayName("Multipart требует CSRF header до разбора тела и не принимает клиентского владельца")
    fun csrfAndForgedOwner() {
        val (owner, ownerId) = account(); val (_, otherId) = account()
        val content = "another private file".toByteArray()
        assertEquals(403, owner.multipart(FILES_API, VALID_FILE_NAME, content, null).statusCode())
        assertEquals(403, owner.multipart(FILES_API, VALID_FILE_NAME, content, browser().csrf()).statusCode())
        assertEquals(0, jdbc.queryForObject("select count(*) from stored_files where owner_id=?", Int::class.java, ownerId))
        val response = owner.multipart(FILES_API, VALID_FILE_NAME, content, owner.csrf(), mapOf("ownerId" to otherId.toString(), "objectKey" to "forged", "status" to "READY"))
        assertEquals(201, response.statusCode())
        val id = UUID.fromString(mapper.readTree(response.body())["id"].asString())
        val key = jdbc.queryForObject("select object_key from stored_files where id=?", String::class.java, id)!!
        assertEquals("files/$id", key)
        assertEquals(ownerId, jdbc.queryForObject("select owner_id from stored_files where id=?", UUID::class.java, id))
        val anonymous = browser()
        assertEquals(401, anonymous.multipart(FILES_API, VALID_FILE_NAME, content, anonymous.csrf()).statusCode())
        mvc.perform(get(FILES_API)).andExpect(status().isUnauthorized)
        mvc.perform(post(FILES_API)).andExpect(status().isForbidden)
    }

    @Test
    @DisplayName("Пустой и слишком большой файл отклоняются, старый HTML upload закрыт")
    fun invalidAndHtmlUpload() {
        val (b, _) = account()
        assertEquals(400, b.multipart(FILES_API, "empty.txt", byteArrayOf(), b.csrf()).statusCode())
        val tooLarge = b.multipart(FILES_API, "large.bin", ByteArray(FilePolicy.MAX_BYTES + 1), b.csrf())
        assertEquals(413, tooLarge.statusCode())
        assertEquals("FILE_TOO_LARGE", mapper.readTree(tooLarge.body())["code"].asString())
        val page = b.get(FILES_PAGE)
        assertTrue(page.body().contains("/assets/"))
        assertEquals("no-referrer", page.headers().firstValue("referrer-policy").get())
        assertEquals(403, b.multipart(FILES_PAGE, "normal.txt", "html file".toByteArray(), b.csrf()).statusCode())

    }

    @Test
    @DisplayName("Прямой вызов metadata bean проверяет владельца, незавершённые файлы недоступны")
    fun methodSecurityAndLifecycle() {
        val (b, ownerId) = account(); val (_, otherId) = account(); val id = upload(b)
        try {
            SecurityContextHolder.getContext().authentication = UsernamePasswordAuthenticationToken(
                AccountPrincipal(otherId, "other", "erased"), null, listOf(org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_USER")),
            )
            assertThrows(AccessDeniedException::class.java) { catalog.loadForDownload(id) }
            assertThrows(AccessDeniedException::class.java) { service.download(id) }
            SecurityContextHolder.clearContext()
            assertThrows(org.springframework.security.authentication.AuthenticationCredentialsNotFoundException::class.java) { service.list() }
        } finally { SecurityContextHolder.clearContext() }
        for (state in listOf("UPLOADING", "FAILED")) {
            jdbc.update("update stored_files set status=? where id=?", state, id)
            assertEquals(403, b.get(TestEndpoints.fileContent(id)).statusCode())
            assertEquals("[]", b.get(FILES_API).body())
        }
    }

    @Test
    @DisplayName("S3 закрыт анонимно, объект сохраняется после перезапуска контейнера")
    fun privacyAndPersistence() {
        val (b, _) = account(); val bytes = "persistent content".toByteArray(); val id = upload(b, bytes)
        val endpoint = TestObjectStore.endpoint()
        val direct = HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("$endpoint/${TestObjectStore.bucket}/files/$id")).GET().build(), HttpResponse.BodyHandlers.discarding())
        assertEquals(403, direct.statusCode())
        val docker = org.testcontainers.DockerClientFactory.instance().client()
        docker.restartContainerCmd(TestObjectStore.container.containerId).exec()
        try {
            org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(30)).until {
                try {
                    s3.putObject({ it.bucket(TestObjectStore.bucket).key("restart-probe") }, software.amazon.awssdk.core.sync.RequestBody.fromString("ready"))
                    true
                } catch (_: software.amazon.awssdk.core.exception.SdkException) { false }
            }
        } catch (failure: org.awaitility.core.ConditionTimeoutException) {
            val log = TestObjectStore.container.logs.replace(TestObjectStore.secret, REDACTED).replace(TestObjectStore.access, REDACTED)
            java.nio.file.Files.writeString(java.nio.file.Path.of("build/s3-restart.log"), log)
            throw failure
        }
        assertArrayEquals(bytes, b.bytes(TestEndpoints.fileContent(id)).body())
    }

    @Test
    @DisplayName("Отсутствующий или повреждённый S3 объект не выдаётся как успешный файл")
    fun missingAndCorruptedObject() {
        val (b, _) = account(); val id = upload(b)
        val objectKey = "files/$id"
        s3.putObject({ it.bucket(TestObjectStore.bucket).key(objectKey) }, software.amazon.awssdk.core.sync.RequestBody.fromString("corrupted"))
        val corrupt = b.get(TestEndpoints.fileContent(id))
        assertEquals(503, corrupt.statusCode())
        assertEquals("FILE_SERVICE_UNAVAILABLE", mapper.readTree(corrupt.body())["code"].asString())
        assertFalse(corrupt.body().contains("corrupted"))
        s3.deleteObject { it.bucket(TestObjectStore.bucket).key(objectKey) }
        assertEquals(503, b.get(TestEndpoints.fileContent(id)).statusCode())
    }

    @Test
    @DisplayName("OpenAPI файлов совпадает с полями metadata и реализованными статусами")
    fun fileContract() {
        val doc = org.yaml.snakeyaml.Yaml().load<Map<String, Any>>(java.nio.file.Files.readString(java.nio.file.Path.of("../docs/openapi.yaml")))
        val paths = doc["paths"] as Map<*, *>
        val uploadSpec = ((paths[FILES_API] as Map<*, *>)["post"] as Map<*, *>)
        assertEquals(setOf("201", "400", "401", "403", "413", "500", "503"), (uploadSpec["responses"] as Map<*, *>).keys)
        val components = doc["components"] as Map<*, *>
        val schema = ((components["schemas"] as Map<*, *>)["FileInfo"] as Map<*, *>)
        val (b, _) = account(); upload(b)
        val info = mapper.readTree(b.get(FILES_API).body())[0]
        assertEquals((schema["required"] as List<*>).toSet(), info.propertyNames().toSet())
        assertEquals(false, schema["additionalProperties"])
        assertTrue(info["sizeBytes"].asLong() > 0)
        assertNotNull(java.time.Instant.parse(info["createdAt"].asString()))
    }

    @Test
    @DisplayName("Файл ровно 10 MiB проходит настоящий multipart, S3 PUT и bounded download")
    fun maximumSizeRoundtrip() {
        val (b, _) = account()
        val bytes = ByteArray(FilePolicy.MAX_BYTES) { (it % 251).toByte() }
        val id = upload(b, bytes, "maximum.bin")
        val download = b.bytes(TestEndpoints.fileContent(id))
        assertEquals(200, download.statusCode())
        assertArrayEquals(bytes, download.body())
    }

    @Test
    @DisplayName("React shell не содержит metadata, содержимое выдаётся как attachment")
    fun filenameEscaping() {
        val (b, _) = account()
        val name = "<img src=x onerror=alert(1)>.html"
        val id = upload(b, "<script>alert(1)</script>".toByteArray(), name)
        val page = b.get(FILES_PAGE)
        assertFalse(page.body().contains("&lt;img"))
        assertFalse(page.body().contains(name))
        val download = b.bytes(TestEndpoints.fileContent(id))
        assertEquals(MediaType.APPLICATION_OCTET_STREAM_VALUE, download.headers().firstValue("content-type").get())
        assertEquals("nosniff", download.headers().firstValue("x-content-type-options").get())
    }

    companion object {
        @JvmStatic @DynamicPropertySource
        fun properties(registry: DynamicPropertyRegistry) { TestDatabase.properties(registry); TestObjectStore.properties(registry) }
    }
}
