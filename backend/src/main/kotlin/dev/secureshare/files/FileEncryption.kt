package dev.secureshare.files

import com.google.crypto.tink.Aead
import com.google.crypto.tink.InsecureSecretKeyAccess
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.TinkProtoKeysetFormat
import com.google.crypto.tink.aead.AeadConfig2026
import com.google.crypto.tink.aead.PredefinedAeadParameters
import dev.secureshare.observability.Stage
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.io.IOException
import java.nio.channels.Channels
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermissions
import java.security.GeneralSecurityException
import java.util.UUID

@Configuration(proxyBeanMethods = false)
class FileEncryptionConfiguration {
    @Bean
    fun fileEncryption(@Value("\${storage.encryption.keyset-path}") keysetPath: String): FileEncryption {
        require(keysetPath.isNotBlank()) { "Укажите FILE_ENCRYPTION_KEYSET" }
        return FileEncryption(FileKeysets.load(Path.of(keysetPath)))
    }
}

object FileKeysets {
    private const val MAX_KEYSET_BYTES = 64 * 1024
    private val parameters = PredefinedAeadParameters.AES256_GCM

    fun generate(path: Path) {
        val handle = KeysetHandle.generateNew(parameters, AeadConfig2026.get())
        val serialized = TinkProtoKeysetFormat.serializeKeyset(handle, InsecureSecretKeyAccess.get(), AeadConfig2026.get())
        try {
            Files.createDirectories(path.toAbsolutePath().parent)
            val permissions = PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"))
            Files.newByteChannel(path, setOf(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE), permissions).use {
                Channels.newOutputStream(it).write(serialized)
            }
        } finally {
            serialized.fill(0)
        }
    }

    fun load(path: Path): Aead {
        try {
            val serialized = Files.newInputStream(path).use { it.readNBytes(MAX_KEYSET_BYTES + 1) }
            try {
                require(serialized.size in 1..MAX_KEYSET_BYTES)
                val handle = TinkProtoKeysetFormat.parseKeyset(serialized, InsecureSecretKeyAccess.get(), AeadConfig2026.get())
                require((0 until handle.size()).all { handle.getAt(it).key.parameters == parameters })
                return handle.getPrimitive(AeadConfig2026.get(), Aead::class.java)
            } finally {
                serialized.fill(0)
            }
        } catch (_: IOException) {
            throw IllegalStateException("Не удалось прочитать FILE_ENCRYPTION_KEYSET")
        } catch (_: GeneralSecurityException) {
            throw IllegalStateException("Некорректный FILE_ENCRYPTION_KEYSET")
        } catch (_: IllegalArgumentException) {
            throw IllegalStateException("Некорректный FILE_ENCRYPTION_KEYSET")
        }
    }
}

class FileEncryption(private val aead: Aead) {
    fun encrypt(file: StoredFile, bytes: ByteArray): ByteArray = try {
        aead.encrypt(bytes, context(file))
    } catch (exception: GeneralSecurityException) {
        throw FileUnavailableException(exception, Stage.FILE_ENCRYPTION, fileId = file.id)
    }

    fun decrypt(file: StoredFile, bytes: ByteArray): ByteArray = try {
        aead.decrypt(bytes, context(file))
    } catch (exception: GeneralSecurityException) {
        throw FileUnavailableException(exception, Stage.FILE_INTEGRITY, fileId = file.id)
    }

    private fun context(file: StoredFile): ByteArray =
        "secureshare:file:AEAD_V1:${file.id}:${file.ownerId}".toByteArray(Charsets.US_ASCII)

    companion object {
        // Tink prefix (5), GCM nonce (12) и тег (16).
        const val OVERHEAD = 33
        const val MAX_STORED_BYTES = FilePolicy.MAX_BYTES + OVERHEAD
    }
}

internal fun encryptedObjectKey(id: UUID) = "encrypted/v1/$id"
