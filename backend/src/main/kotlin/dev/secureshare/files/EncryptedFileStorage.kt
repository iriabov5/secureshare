package dev.secureshare.files

import dev.secureshare.observability.Stage
import org.springframework.stereotype.Component

@Component
class EncryptedFileStorage(private val s3: S3Storage, private val encryption: FileEncryption) : ObjectStorage {
    override fun put(file: StoredFile, bytes: ByteArray) {
        require(file.storageFormat == StorageFormat.AEAD_V1)
        val encrypted = encryption.encrypt(file, bytes)
        if (encrypted.size.toLong() != file.ciphertextSizeBytes) {
            throw FileUnavailableException(stage = Stage.FILE_ENCRYPTION, fileId = file.id)
        }
        s3.put(file.objectKey, encrypted)
    }

    override fun get(file: StoredFile): ByteArray {
        val bytes = when (file.storageFormat) {
            StorageFormat.LEGACY_PLAIN -> s3.get(file.objectKey, file.sizeBytes, file.id)
            StorageFormat.AEAD_V1 -> {
                val size = file.ciphertextSizeBytes ?: throw integrityFailure(file)
                encryption.decrypt(file, s3.get(file.objectKey, size, file.id))
            }
        }
        if (bytes.size.toLong() != file.sizeBytes || FilePolicy.sha256(bytes) != file.sha256) {
            throw integrityFailure(file)
        }
        return bytes
    }

    private fun integrityFailure(file: StoredFile) = FileUnavailableException(stage = Stage.FILE_INTEGRITY, fileId = file.id)
}
