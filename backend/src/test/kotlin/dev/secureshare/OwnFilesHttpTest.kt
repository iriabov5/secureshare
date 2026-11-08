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
import dev.secureshare.files.migration.EncryptionMigration
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.services.s3.model.S3Exception
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.mockito.Mockito.*
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean
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
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

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
    @Autowired private lateinit var migration: EncryptionMigration
    @MockitoSpyBean private lateinit var s3Storage: S3Storage
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
        assertEquals(setOf("id", "name", "sizeBytes", "sha256", "createdAt", "storageStatus", "legacyCleanupPending"), info.propertyNames().toSet())
        assertEquals(FilePolicy.sha256(bytes), info["sha256"].asString())
        assertEquals("ENCRYPTED", info["storageStatus"].asString())
        assertFalse(info["legacyCleanupPending"].asBoolean())
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

    private fun assertVerified(browser: Browser, id: UUID) {
        val response = browser.post(TestEndpoints.integrityCheck(id), mapOf("_csrf" to browser.csrf()))
        assertEquals(200, response.statusCode())
        assertTrue(response.headers().firstValue("content-type").orElseThrow().startsWith(MediaType.APPLICATION_JSON_VALUE))
        assertEquals("no-store", response.headers().firstValue("cache-control").orElseThrow())
        assertTrue(response.headers().firstValue("content-disposition").isEmpty)
        val body = mapper.readTree(response.body())
        assertEquals(setOf("status", "checkedAt"), body.propertyNames().toSet())
        assertEquals("VERIFIED", body["status"].asString())
        assertNotNull(Instant.parse(body["checkedAt"].asString()))
    }

    private fun assertIntegrityUnavailable(browser: Browser, id: UUID) {
        val response = browser.post(TestEndpoints.integrityCheck(id), mapOf("_csrf" to browser.csrf()))
        assertEquals(503, response.statusCode())
        val body = mapper.readTree(response.body())
        assertEquals(setOf("code", "message"), body.propertyNames().toSet())
        assertEquals("FILE_SERVICE_UNAVAILABLE", body["code"].asString())
    }

    @Test
    @DisplayName("Проверка целостности требует сессию, CSRF и готовый файл владельца")
    fun integrityCheckAccess() {
        val (owner, _) = account()
        val (other, _) = account()
        val anonymous = browser()
        val id = upload(owner)
        val path = TestEndpoints.integrityCheck(id)
        val csrf = owner.csrf()

        assertVerified(owner, id)
        assertEquals(401, anonymous.post(path, mapOf("_csrf" to anonymous.csrf())).statusCode())
        assertEquals(403, owner.post(path, emptyMap()).statusCode())
        assertEquals(403, owner.post(path, mapOf("_csrf" to "invalid")).statusCode())
        val forbidden = other.post(path, mapOf("_csrf" to other.csrf()))
        assertEquals(403, forbidden.statusCode())
        val missing = owner.post(TestEndpoints.integrityCheck(UUID.randomUUID()), mapOf("_csrf" to csrf))
        assertEquals(403, missing.statusCode())
        assertEquals(forbidden.body(), missing.body())
        val invalid = owner.post("$FILES_API/not-a-uuid/integrity-check", mapOf("_csrf" to csrf))
        assertEquals(400, invalid.statusCode())
        assertEquals("INVALID_FILE", mapper.readTree(invalid.body())["code"].asString())

        jdbc.update("update stored_files set status='FAILED' where id=?", id)
        val unready = owner.post(path, mapOf("_csrf" to csrf))
        assertEquals(403, unready.statusCode())
        assertEquals(forbidden.body(), unready.body())
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
        assertEquals("encrypted/v1/$id", key)
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
        val direct = HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("$endpoint/${TestObjectStore.bucket}/encrypted/v1/$id")).GET().build(), HttpResponse.BodyHandlers.discarding())
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
        val objectKey = "encrypted/v1/$id"
        s3.putObject({ it.bucket(TestObjectStore.bucket).key(objectKey) }, software.amazon.awssdk.core.sync.RequestBody.fromString("corrupted"))
        val corrupt = b.get(TestEndpoints.fileContent(id))
        assertEquals(503, corrupt.statusCode())
        assertEquals("FILE_SERVICE_UNAVAILABLE", mapper.readTree(corrupt.body())["code"].asString())
        assertFalse(corrupt.body().contains("corrupted"))
        s3.deleteObject { it.bucket(TestObjectStore.bucket).key(objectKey) }
        assertEquals(503, b.get(TestEndpoints.fileContent(id)).statusCode())
        assertIntegrityUnavailable(b, id)
    }

    @Test
    @DisplayName("В бакете шифротекст, а подмена объектом того же размера и порча тега отклоняются")
    fun encryptedObjectAndTampering() {
        val (browser, _) = account()
        val bytes = "same data".toByteArray()
        val first = upload(browser, bytes)
        val second = upload(browser, bytes)
        val firstKey = encryptedObjectKey(first)
        val ciphertext = rawObject(firstKey)
        val other = rawObject(encryptedObjectKey(second))
        assertEquals(bytes.size + FileEncryption.OVERHEAD, ciphertext.size)
        assertFalse(ciphertext.contentEquals(bytes))
        assertFalse(ciphertext.contentEquals(other))
        assertArrayEquals(bytes, browser.bytes(TestEndpoints.fileContent(first)).body())

        for (altered in listOf(other, ciphertext.clone().apply { this[lastIndex] = (last().toInt() xor 1).toByte() })) {
            putRaw(firstKey, altered)
            val response = browser.get(TestEndpoints.fileContent(first))
            assertEquals(503, response.statusCode())
            assertEquals("FILE_SERVICE_UNAVAILABLE", mapper.readTree(response.body())["code"].asString())
            assertFalse(response.body().contains("same data"))
            assertIntegrityUnavailable(browser, first)
        }
    }

    @Test
    @DisplayName("Шифрование требует сессию, CSRF и владение готовым файлом до обращения к S3")
    fun encryptAccess() {
        val (browser, owner) = account()
        val (other, _) = account()
        val anonymous = browser()
        val id = legacyFile(owner, "legacy".toByteArray())
        val path = TestEndpoints.encryptFile(id)
        val csrf = browser.csrf()
        clearInvocations(s3Storage)

        assertEquals(401, anonymous.post(path, mapOf("_csrf" to anonymous.csrf())).statusCode())
        assertEquals(403, browser.post(path, emptyMap()).statusCode())
        assertEquals(403, browser.post(path, mapOf("_csrf" to "invalid")).statusCode())
        val forbidden = other.post(path, mapOf("_csrf" to other.csrf()))
        assertEquals(403, forbidden.statusCode())
        val missing = browser.post(TestEndpoints.encryptFile(UUID.randomUUID()), mapOf("_csrf" to csrf))
        assertEquals(403, missing.statusCode())
        assertEquals(forbidden.body(), missing.body())
        val invalid = browser.post("$FILES_API/not-a-uuid/encrypt", mapOf("_csrf" to csrf))
        assertEquals(400, invalid.statusCode())
        assertEquals("INVALID_FILE", mapper.readTree(invalid.body())["code"].asString())
        jdbc.update("update stored_files set status='FAILED' where id=?", id)
        val unready = browser.post(path, mapOf("_csrf" to csrf))
        assertEquals(403, unready.statusCode())
        assertEquals(forbidden.body(), unready.body())
        verifyNoInteractions(s3Storage)
    }

    @Test
    @DisplayName("Перенос сохраняет исходник при ошибке и завершает очистку отдельным повтором")
    fun encryptAndRetryCleanup() {
        val (browser, owner) = account()
        val bytes = "legacy payload".toByteArray()
        val id = legacyFile(owner, bytes)
        val oldKey = "files/$id"
        val path = TestEndpoints.encryptFile(id)
        val csrf = mapOf("_csrf" to browser.csrf())
        val before = mapper.readTree(browser.get(FILES_API).body())[0]
        putRaw(oldKey, ByteArray(bytes.size))

        val corrupt = browser.post(path, csrf)
        assertEquals(503, corrupt.statusCode())
        assertEquals("FILE_SERVICE_UNAVAILABLE", mapper.readTree(corrupt.body())["code"].asString())
        assertEquals(oldKey, activeObjectKey(id))
        assertEquals(before, mapper.readTree(browser.get(FILES_API).body())[0])

        putRaw(oldKey, bytes)
        doThrow(FileUnavailableException(stage = dev.secureshare.observability.Stage.S3_DELETE, fileId = id))
            .doCallRealMethod().`when`(s3Storage).delete(oldKey, id)
        assertEquals(503, browser.post(path, csrf).statusCode())
        val pending = mapper.readTree(browser.get(FILES_API).body())[0]
        assertEquals("ENCRYPTED", pending["storageStatus"].asString())
        assertTrue(pending["legacyCleanupPending"].asBoolean())
        assertArrayEquals(bytes, rawObject(oldKey))
        assertArrayEquals(bytes, browser.bytes(TestEndpoints.fileContent(id)).body())

        val done = browser.post(path, csrf)
        assertEquals(200, done.statusCode())
        assertEquals("no-store", done.headers().firstValue("cache-control").orElseThrow())
        val info = mapper.readTree(done.body())
        assertEquals(before.propertyNames(), info.propertyNames())
        for (field in listOf("id", "name", "sizeBytes", "sha256", "createdAt")) assertEquals(before[field], info[field])
        assertEquals("ENCRYPTED", info["storageStatus"].asString())
        assertFalse(info["legacyCleanupPending"].asBoolean())
        assertEquals(404, assertThrows(S3Exception::class.java) { rawObject(oldKey) }.statusCode())

        clearInvocations(s3Storage)
        assertEquals(done.body(), browser.post(path, csrf).body())
        verifyNoInteractions(s3Storage)
    }

    @Test
    @DisplayName("Параллельный перенос сериализуется, скачивание и проверка перечитывают новый ключ")
    fun encryptDuringRead() {
        val (browser, owner) = account()
        val bytes = "legacy payload".toByteArray()
        val id = legacyFile(owner, bytes)
        val oldKey = "files/$id"
        val csrf = mapOf("_csrf" to browser.csrf())
        val path = TestEndpoints.encryptFile(id)
        val oldReaders = CountDownLatch(2)
        val resumeReaders = CountDownLatch(1)
        val migrationReads = CountDownLatch(1)
        val resumeMigration = CountDownLatch(1)
        val readers = AtomicInteger()
        doAnswer { call ->
            if (readers.incrementAndGet() <= 2) {
                oldReaders.countDown()
                check(resumeReaders.await(10, TimeUnit.SECONDS))
            } else {
                migrationReads.countDown()
                check(resumeMigration.await(10, TimeUnit.SECONDS))
            }
            call.callRealMethod()
        }.`when`(s3Storage).get(oldKey, bytes.size.toLong(), id)

        Executors.newVirtualThreadPerTaskExecutor().use { executor ->
            val download = CompletableFuture.supplyAsync({ browser.bytes(TestEndpoints.fileContent(id)) }, executor)
            val integrity = CompletableFuture.supplyAsync({ browser.post(TestEndpoints.integrityCheck(id), csrf) }, executor)
            try {
                assertTrue(oldReaders.await(5, TimeUnit.SECONDS))
                val encrypt = CompletableFuture.supplyAsync({ browser.post(path, csrf) }, executor)
                assertTrue(migrationReads.await(5, TimeUnit.SECONDS))
                val duplicate = browser.post(path, csrf)
                assertEquals(409, duplicate.statusCode())
                assertEquals("FILE_ENCRYPTION_IN_PROGRESS", mapper.readTree(duplicate.body())["code"].asString())
                assertEquals(EncryptionMigration.Result(0, 0, 1), migration.run())
                resumeMigration.countDown()
                assertEquals(200, encrypt.get(10, TimeUnit.SECONDS).statusCode())
            } finally {
                resumeMigration.countDown()
                resumeReaders.countDown()
            }
            assertArrayEquals(bytes, download.get(10, TimeUnit.SECONDS).body())
            assertEquals(200, integrity.get(10, TimeUnit.SECONDS).statusCode())
            assertEquals(3, readers.get())
        }
    }

    private fun legacyFile(owner: UUID, bytes: ByteArray): UUID {
        val id = UUID.randomUUID()
        val key = "files/$id"
        putRaw(key, bytes)
        jdbc.update(
            """insert into stored_files
                (id, owner_id, original_name, size_bytes, sha256, object_key, status, created_at, storage_format)
                values (?, ?, ?, ?, ?, ?, 'READY', now(), 'LEGACY_PLAIN')""",
            id, owner, "legacy.txt", bytes.size.toLong(), FilePolicy.sha256(bytes), key,
        )
        return id
    }

    @Test
    @DisplayName("Перенос проверяет исходник, сохраняет UUID и умеет повторить незавершённую очистку")
    fun migrateLegacyFile() {
        val (browser, owner) = account()
        val bytes = "legacy payload".toByteArray()
        val id = legacyFile(owner, bytes)
        val oldKey = "files/$id"
        val newKey = encryptedObjectKey(id)
        val newFile = upload(browser)
        fun statuses() = mapper.readTree(browser.get(FILES_API).body()).associate { it["id"].asString() to it["storageStatus"].asString() }
        assertEquals(mapOf(id.toString() to "PENDING_MIGRATION", newFile.toString() to "ENCRYPTED"), statuses())

        putRaw(oldKey, ByteArray(bytes.size))
        assertIntegrityUnavailable(browser, id)
        assertEquals(1L, migration.run().remaining)
        assertEquals(oldKey, activeObjectKey(id))
        putRaw(oldKey, bytes)
        assertVerified(browser, id)
        assertArrayEquals(bytes, browser.bytes(TestEndpoints.fileContent(id)).body())

        assertEquals(EncryptionMigration.Result(1, 1, 0), migration.run())
        assertEquals(newKey, activeObjectKey(id))
        assertEquals("ENCRYPTED", statuses()[id.toString()])
        assertVerified(browser, id)
        assertArrayEquals(bytes, browser.bytes(TestEndpoints.fileContent(id)).body())
        val encrypted = rawObject(newKey)
        assertFalse(encrypted.contentEquals(bytes))
        assertEquals(404, assertThrows(S3Exception::class.java) { rawObject(oldKey) }.statusCode())

        // Состояние после подтверждённого переключения и до удаления оригинала.
        putRaw(oldKey, bytes)
        jdbc.update("update stored_files set legacy_object_key=? where id=?", oldKey, id)
        putRaw(newKey, ByteArray(encrypted.size))
        assertEquals(EncryptionMigration.Result(0, 0, 1), migration.run())
        assertArrayEquals(bytes, rawObject(oldKey))
        putRaw(newKey, encrypted)
        assertEquals(EncryptionMigration.Result(0, 1, 0), migration.run())
        assertEquals(EncryptionMigration.Result(0, 0, 0), migration.run())
    }

    private fun activeObjectKey(id: UUID) =
        jdbc.queryForObject("select object_key from stored_files where id=?", String::class.java, id)

    private fun rawObject(key: String) =
        s3.getObjectAsBytes { it.bucket(TestObjectStore.bucket).key(key) }.asByteArray()

    private fun putRaw(key: String, bytes: ByteArray) {
        s3.putObject({ it.bucket(TestObjectStore.bucket).key(key) }, RequestBody.fromBytes(bytes))
    }

    @Test
    @DisplayName("OpenAPI файлов совпадает с полями metadata и реализованными статусами")
    fun fileContract() {
        val doc = org.yaml.snakeyaml.Yaml().load<Map<String, Any>>(java.nio.file.Files.readString(java.nio.file.Path.of("../docs/openapi.yaml")))
        val paths = doc["paths"] as Map<*, *>
        val uploadSpec = ((paths[FILES_API] as Map<*, *>)["post"] as Map<*, *>)
        assertEquals(setOf("201", "400", "401", "403", "413", "500", "503"), (uploadSpec["responses"] as Map<*, *>).keys)
        val checkSpec = (paths["$FILES_API/{id}/integrity-check"] as Map<*, *>)["post"] as Map<*, *>
        assertEquals(setOf("200", "400", "401", "403", "500", "503"), (checkSpec["responses"] as Map<*, *>).keys)
        val encryptSpec = (paths["$FILES_API/{id}/encrypt"] as Map<*, *>)["post"] as Map<*, *>
        assertEquals(setOf("200", "400", "401", "403", "409", "500", "503"), (encryptSpec["responses"] as Map<*, *>).keys)
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
