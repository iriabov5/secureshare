package dev.secureshare.files

import jakarta.persistence.*
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "stored_files")
class StoredFile(
    @Id val id: UUID,
    @Column(name = "owner_id", nullable = false) val ownerId: UUID,
    @Column(name = "original_name", nullable = false) val originalName: String,
    @Column(name = "size_bytes", nullable = false) val sizeBytes: Long,
    @Column(nullable = false, columnDefinition = "char(64)")
    @org.hibernate.annotations.JdbcTypeCode(org.hibernate.type.SqlTypes.CHAR) val sha256: String,
    @Column(name = "object_key", nullable = false, length = 80) val objectKey: String,
    @Column(nullable = false, length = 16) val status: String = "UPLOADING",
    @Column(name = "created_at", nullable = false) val createdAt: Instant,
    @Enumerated(EnumType.STRING)
    @Column(name = "storage_format", nullable = false, length = 16) val storageFormat: StorageFormat,
    @Column(name = "ciphertext_size_bytes") val ciphertextSizeBytes: Long? = null,
    @Column(name = "legacy_object_key", length = 80) val legacyObjectKey: String? = null,
)

enum class StorageFormat { LEGACY_PLAIN, AEAD_V1 }

interface StoredFileRepository : JpaRepository<StoredFile, UUID> {
    fun findAllByOwnerIdAndStatusOrderByCreatedAtDescIdDesc(ownerId: UUID, status: String): List<StoredFile>
    fun findByIdAndStatus(id: UUID, status: String): StoredFile?

    @Modifying
    @Query("update StoredFile f set f.status = :status where f.id = :id and f.status = 'UPLOADING'")
    fun finish(id: UUID, status: String): Int
}

enum class StorageStatus { ENCRYPTED, PENDING_MIGRATION }

data class FileInfo(
    val id: UUID,
    val name: String,
    val sizeBytes: Long,
    val sha256: String,
    val createdAt: Instant,
    val storageStatus: StorageStatus,
    val legacyCleanupPending: Boolean,
)

fun StoredFile.toInfo(): FileInfo {
    val storageStatus = when (storageFormat) {
        StorageFormat.AEAD_V1 -> StorageStatus.ENCRYPTED
        StorageFormat.LEGACY_PLAIN -> StorageStatus.PENDING_MIGRATION
    }
    return FileInfo(id, originalName, sizeBytes, sha256, createdAt, storageStatus, legacyObjectKey != null)
}
