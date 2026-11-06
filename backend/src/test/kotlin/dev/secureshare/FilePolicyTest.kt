package dev.secureshare

import dev.secureshare.files.*
import dev.secureshare.security.AccountPrincipal
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.mockito.Mockito.*
import org.springframework.dao.DataAccessResourceFailureException
import org.springframework.mock.env.MockEnvironment
import org.springframework.mock.web.MockMultipartFile
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import java.time.Clock
import java.time.Instant
import java.util.UUID

private const val FILE_NAME = "file.txt"

class FilePolicyTest {
    @ParameterizedTest
    @ValueSource(strings = ["", " ", ".", "..", "../secret", "a/b", "a\\b", "line\r\nbreak", "control\u0000", "delete\u007f"])
    @DisplayName("Имя файла не допускает пути, управляющие символы и пустые значения")
    fun invalidNames(name: String) { assertThrows(InvalidFileException::class.java) { FilePolicy.validate(name, 1) } }

    @Test
    @DisplayName("Размер и имя ограничиваются фактическими байтами, включая UTF-8")
    fun boundaries() {
        assertThrows(InvalidFileException::class.java) { FilePolicy.validate(null, 1) }
        assertThrows(InvalidFileException::class.java) { FilePolicy.validate("ok.txt", 0) }
        assertThrows(FileTooLargeException::class.java) { FilePolicy.validate("ok", FilePolicy.MAX_BYTES.toLong() + 1) }
        assertThrows(InvalidFileException::class.java) { FilePolicy.validate("я".repeat(128), 1) }
        assertEquals("я".repeat(127) + "a", FilePolicy.validate("я".repeat(127) + "a", FilePolicy.MAX_BYTES.toLong()))
        assertEquals("a".repeat(255), FilePolicy.validate("a".repeat(255), 1))
    }

    @Test
    @DisplayName("S3 endpoint допускает HTTP только на loopback в local-профиле и требует полные credentials")
    fun configurationBoundaries() {
        val configuration = S3Configuration()
        val normal = MockEnvironment(); val local = MockEnvironment().withProperty("spring.profiles.active", "local")
        local.setActiveProfiles("local")
        for (endpoint in listOf("http://localhost:8333", "http://external.example", "ftp://localhost", "https://user:secret@example.com", "https://example.com?token=bad")) {
            assertThrows(IllegalArgumentException::class.java) { configuration.s3Client(S3Properties(endpoint = endpoint), normal) }
        }
        assertThrows(IllegalArgumentException::class.java) { configuration.s3Client(S3Properties(accessKey = "only-one"), local) }
        assertThrows(IllegalArgumentException::class.java) { configuration.s3Client(S3Properties(bucket = ""), local) }
        assertThrows(IllegalArgumentException::class.java) { configuration.s3Client(S3Properties(region = ""), local) }
        configuration.s3Client(S3Properties(endpoint = "http://localhost:8333", accessKey = "test", secretKey = "test"), local).close()
        configuration.s3Client(S3Properties(endpoint = "https://example.com"), normal).close()
        configuration.s3Client(S3Properties(), normal).close()
    }

    @Test
    @DisplayName("Сбой финализации не выдаёт успех и не удаляет возможно подтверждённый S3 объект")
    fun failedFinalization() {
        val catalog = mock(FileCatalog::class.java); val repository = mock(StoredFileRepository::class.java); val storage = mock(ObjectStorage::class.java)
        val owner = UUID.randomUUID(); val bytes = "file".toByteArray()
        val record = StoredFile(UUID.randomUUID(), owner, FILE_NAME, 4, FilePolicy.sha256(bytes), "files/key", createdAt = Instant.EPOCH)
        `when`(catalog.create(owner, FILE_NAME, bytes)).thenReturn(record)
        doThrow(DataAccessResourceFailureException("offline")).`when`(catalog).ready(record.id)
        val service = FileService(catalog, repository, storage)
        try {
            SecurityContextHolder.getContext().authentication = UsernamePasswordAuthenticationToken(AccountPrincipal(owner, "owner", "erased"), null, emptyList())
            val failure = assertThrows(FileUnavailableException::class.java) { service.upload(MockMultipartFile("file", FILE_NAME, "text/plain", bytes)) }
            assertInstanceOf(DataAccessResourceFailureException::class.java, failure.cause)
            assertEquals(dev.secureshare.observability.Stage.METADATA_READY, failure.stage)
            assertEquals(dev.secureshare.observability.Outcome.UNKNOWN, failure.outcome)
            verify(storage).put(record.objectKey, bytes)
            verify(catalog, never()).failed(record.id)
        } finally { SecurityContextHolder.clearContext() }
    }

    @Test
    @DisplayName("S3 PUT failure остаётся исходной ошибкой даже при недоступной записи FAILED")
    fun failedUploadStateUpdate() {
        val catalog = mock(FileCatalog::class.java); val repository = mock(StoredFileRepository::class.java); val storage = mock(ObjectStorage::class.java)
        val owner = UUID.randomUUID(); val bytes = "file".toByteArray()
        val record = StoredFile(UUID.randomUUID(), owner, FILE_NAME, 4, FilePolicy.sha256(bytes), "files/key", createdAt = Instant.EPOCH)
        `when`(catalog.create(owner, FILE_NAME, bytes)).thenReturn(record)
        val unavailable = FileUnavailableException()
        doThrow(unavailable).`when`(storage).put(record.objectKey, bytes)
        val service = FileService(catalog, repository, storage)
        try {
            SecurityContextHolder.getContext().authentication = UsernamePasswordAuthenticationToken(AccountPrincipal(owner, "owner", "erased"), null, emptyList())
            for (failure in listOf(DataAccessResourceFailureException("offline"), org.springframework.transaction.CannotCreateTransactionException("offline"))) {
                doThrow(failure).`when`(catalog).failed(record.id)
                assertSame(unavailable, assertThrows(FileUnavailableException::class.java) { service.upload(MockMultipartFile("file", FILE_NAME, "text/plain", bytes)) })
            }
            verify(catalog, never()).ready(record.id)
        } finally { SecurityContextHolder.clearContext() }
    }

    @Test
    @DisplayName("Metadata lifecycle не принимает повторную финализацию и сохраняет серверное время")
    fun catalogState() {
        val repository = mock(StoredFileRepository::class.java)
        val clock = Clock.fixed(Instant.EPOCH, java.time.ZoneOffset.UTC)
        val catalog = FileCatalog(repository, clock)
        val id = UUID.randomUUID()
        `when`(repository.finish(id, "READY")).thenReturn(0)
        assertThrows(FileUnavailableException::class.java) { catalog.ready(id) }
        catalog.failed(id)
        verify(repository).finish(id, "FAILED")
        assertThrows(org.springframework.security.access.AccessDeniedException::class.java) { catalog.loadForDownload(id) }
    }

    @Test
    @DisplayName("Ошибки чтения multipart обрабатываются как недоступность, отсутствующая часть — как невалидный файл")
    fun multipartReadFailure() {
        val service = FileService(mock(FileCatalog::class.java), mock(StoredFileRepository::class.java), mock(ObjectStorage::class.java))
        assertThrows(InvalidFileException::class.java) { service.upload(null) }
        val part = mock(org.springframework.web.multipart.MultipartFile::class.java)
        `when`(part.originalFilename).thenReturn(FILE_NAME); `when`(part.size).thenReturn(4)
        `when`(part.bytes).thenThrow(java.io.IOException("offline"))
        assertThrows(FileUnavailableException::class.java) { service.upload(part) }
    }
}
