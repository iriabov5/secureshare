package dev.secureshare

import com.google.crypto.tink.Aead
import dev.secureshare.files.*
import dev.secureshare.observability.Stage
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.mockito.Mockito.*
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.security.GeneralSecurityException
import java.time.Instant
import java.util.UUID

class FileEncryptionTest {
    @TempDir private lateinit var directory: Path

    @Test
    @DisplayName("Ключ создаётся один раз, повторная загрузка ключа читает прежний файл")
    fun persistentKey() {
        val path = directory.resolve("files.tink-keyset")
        FileKeysets.generate(path)
        assertEquals(PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(path))
        assertThrows(FileAlreadyExistsException::class.java) { FileKeysets.generate(path) }
        val bytes = "secret payload".toByteArray()
        val file = record(bytes)
        val encrypted = FileEncryption(FileKeysets.load(path)).encrypt(file, bytes)
        val reopened = FileEncryption(FileKeysets.load(path))
        assertArrayEquals(bytes, reopened.decrypt(file, encrypted))

        val different = directory.resolve("different.tink-keyset")
        FileKeysets.generate(different)
        val otherKey = FileEncryption(FileKeysets.load(different))
        assertThrows(FileUnavailableException::class.java) { otherKey.decrypt(file, encrypted) }
        assertThrows(FileUnavailableException::class.java) {
            reopened.decrypt(record(bytes, id = file.id), encrypted)
        }
    }

    @Test
    @DisplayName("Обязательный keyset проверяется при конфигурации без вывода содержимого")
    fun invalidKeysets() {
        val config = FileEncryptionConfiguration()
        assertThrows(IllegalArgumentException::class.java) { config.fileEncryption("") }
        val path = directory.resolve("missing.tink-keyset")
        assertThrows(IllegalStateException::class.java) { config.fileEncryption(path.toString()) }
        val invalid = "malformed-secret"
        Files.writeString(path, invalid)
        val error = assertThrows(IllegalStateException::class.java) { config.fileEncryption(path.toString()) }
        assertFalse(error.toString().contains(invalid))
        assertNull(error.cause)
    }

    @Test
    @DisplayName("Отказ шифрования не приводит к записи байтов в бакет")
    fun encryptionFailureStopsPut() {
        val aead = mock(Aead::class.java)
        val s3 = mock(S3Storage::class.java)
        val bytes = "data".toByteArray()
        val file = record(bytes)
        `when`(aead.encrypt(any(ByteArray::class.java), any(ByteArray::class.java)))
            .thenThrow(GeneralSecurityException("encryption unavailable"))
        val storage = EncryptedFileStorage(s3, FileEncryption(aead))

        val error = assertThrows(FileUnavailableException::class.java) { storage.put(file, bytes) }

        assertEquals(Stage.FILE_ENCRYPTION, error.stage)
        verifyNoInteractions(s3)
    }

    private fun record(bytes: ByteArray, id: UUID = UUID.randomUUID()) = StoredFile(
        id, UUID.randomUUID(), "file.txt", bytes.size.toLong(), FilePolicy.sha256(bytes),
        encryptedObjectKey(id), createdAt = Instant.EPOCH, storageFormat = StorageFormat.AEAD_V1,
        ciphertextSizeBytes = bytes.size.toLong() + FileEncryption.OVERHEAD,
    )
}
