package dev.secureshare.files

import org.springframework.security.access.AccessDeniedException
import org.springframework.security.access.prepost.PostAuthorize
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant
import java.util.UUID

@Service
class FileCatalog(private val files: StoredFileRepository, private val clock: Clock) {
    @Transactional
    fun create(ownerId: UUID, name: String, bytes: ByteArray): StoredFile {
        val id = UUID.randomUUID()
        return files.saveAndFlush(StoredFile(
            id, ownerId, name, bytes.size.toLong(), FilePolicy.sha256(bytes), encryptedObjectKey(id),
            createdAt = Instant.now(clock), storageFormat = StorageFormat.AEAD_V1,
            ciphertextSizeBytes = bytes.size.toLong() + FileEncryption.OVERHEAD,
        ))
    }

    @Transactional
    fun ready(id: UUID) {
        if (files.finish(id, "READY") != 1) throw FileUnavailableException()
    }

    @Transactional
    fun failed(id: UUID) { files.finish(id, "FAILED") }

    // Проверяется только metadata. Байты S3 читаются после возврата из AOP proxy.
    @PreAuthorize("isAuthenticated()")
    @PostAuthorize("returnObject.ownerId == authentication.principal.accountId")
    @Transactional(readOnly = true)
    fun loadForDownload(id: UUID): StoredFile =
        files.findByIdAndStatus(id, "READY") ?: throw AccessDeniedException("File not available")
}
