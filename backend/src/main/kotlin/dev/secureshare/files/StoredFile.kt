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
)

interface StoredFileRepository : JpaRepository<StoredFile, UUID> {
    fun findAllByOwnerIdAndStatusOrderByCreatedAtDescIdDesc(ownerId: UUID, status: String): List<StoredFile>
    fun findByIdAndStatus(id: UUID, status: String): StoredFile?

    @Modifying
    @Query("update StoredFile f set f.status = :status where f.id = :id and f.status = 'UPLOADING'")
    fun finish(id: UUID, status: String): Int
}

data class FileInfo(val id: UUID, val name: String, val sizeBytes: Long, val sha256: String, val createdAt: Instant)

fun StoredFile.toInfo() = FileInfo(id, originalName, sizeBytes, sha256, createdAt)
