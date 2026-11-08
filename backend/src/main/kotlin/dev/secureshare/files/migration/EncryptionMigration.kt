package dev.secureshare.files.migration

import dev.secureshare.files.EncryptedFileStorage
import dev.secureshare.files.FileEncryption
import dev.secureshare.files.FileUnavailableException
import dev.secureshare.files.S3Storage
import dev.secureshare.files.StorageFormat
import dev.secureshare.files.StoredFile
import dev.secureshare.files.encryptedObjectKey
import dev.secureshare.observability.AppLog
import dev.secureshare.observability.Operation
import dev.secureshare.observability.Stage
import org.springframework.dao.DataAccessException
import org.springframework.jdbc.core.ConnectionCallback
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.datasource.SingleConnectionDataSource
import org.springframework.security.access.AccessDeniedException
import org.springframework.stereotype.Component
import java.sql.Connection
import java.util.UUID

@Component
class EncryptionMigration(private val jdbc: JdbcTemplate, private val files: EncryptedFileStorage, private val s3: S3Storage) {
    data class Result(val migrated: Int, val cleaned: Int, val remaining: Long)

    fun run(): Result = withSession { connection, db ->
        withLock(connection, db, BATCH_LOCK) { migratePending(connection, db) }
    }

    fun encrypt(id: UUID, ownerId: UUID): StoredFile = withSession { connection, db ->
        withLock(connection, db, id.toString()) {
            val file = readyFile(db, id)?.takeIf { it.ownerId == ownerId }
                ?: throw AccessDeniedException("File not available")
            finish(db, file)
        }
    }

    private fun <T : Any> withSession(action: (Connection, JdbcTemplate) -> T): T =
        requireNotNull(jdbc.execute(ConnectionCallback { connection ->
            check(connection.autoCommit) { "Миграции нужен режим autocommit" }
            action(connection, JdbcTemplate(SingleConnectionDataSource(connection, true)))
        }))

    private fun <T> withLock(connection: Connection, db: JdbcTemplate, key: String, action: () -> T): T {
        val locked = lockCommand(connection) {
            db.queryForObject("select pg_try_advisory_lock(hashtextextended(?, 319283467120))", Boolean::class.java, key) == true
        }
        if (!locked) throw FileEncryptionInProgressException()
        val result = try {
            action()
        } catch (failure: Throwable) {
            try {
                unlock(connection, db, key)
            } catch (unlockFailure: RuntimeException) {
                failure.addSuppressed(unlockFailure)
            }
            throw failure
        }
        unlock(connection, db, key)
        return result
    }

    private fun unlock(connection: Connection, db: JdbcTemplate, key: String) {
        lockCommand(connection) {
            if (db.queryForObject("select pg_advisory_unlock(hashtextextended(?, 319283467120))", Boolean::class.java, key) != true) {
                throw FileUnavailableException(stage = Stage.METADATA_READY)
            }
        }
    }

    private fun <T> lockCommand(connection: Connection, action: () -> T): T =
        try {
            action()
        } catch (failure: RuntimeException) {
            // При неясном состоянии блокировки закрываем сессию, чтобы она не вернулась в пул с lock.
            try {
                connection.abort { it.run() }
            } catch (abortFailure: java.sql.SQLException) {
                failure.addSuppressed(abortFailure)
            }
            throw failure
        }

    private fun readyFile(db: JdbcTemplate, id: UUID): StoredFile? =
        db.query("select * from stored_files where id=? and status='READY'", fileRow, id).singleOrNull()

    private fun migratePending(connection: Connection, db: JdbcTemplate): Result {
        var after: UUID? = null
        var migrated = 0
        var cleaned = 0
        while (true) {
            val batch: List<UUID> = db.query(
                "select id from stored_files where $PENDING and (cast(? as uuid) is null or id > ?) order by id limit 50",
                fileIdRow, after, after,
            )
            if (batch.isEmpty()) break
            for (id in batch) {
                if (migrateOne(connection, db, id) { migrated++ }) cleaned++
            }
            after = batch.last()
        }
        val remaining = requireNotNull(db.queryForObject("select count(*) from stored_files where $PENDING", Long::class.javaObjectType))
        return Result(migrated, cleaned, remaining)
    }

    private fun migrateOne(connection: Connection, db: JdbcTemplate, id: UUID, onSwitch: () -> Unit): Boolean =
        try {
            withLock(connection, db, id.toString()) {
                val file = readyFile(db, id) ?: return@withLock false
                if (file.storageFormat == StorageFormat.AEAD_V1 && file.legacyObjectKey == null) return@withLock false
                finish(db, file, onSwitch)
                true
            }
        } catch (_: FileEncryptionInProgressException) {
            false
        } catch (exception: FileUnavailableException) {
            AppLog.failure(Operation.FILE_MIGRATION, exception, fileId = id)
            false
        } catch (exception: DataAccessException) {
            AppLog.failure(Operation.FILE_MIGRATION, exception, Stage.METADATA_READY, id)
            false
        }

    private fun finish(db: JdbcTemplate, file: StoredFile, onSwitch: () -> Unit = {}): StoredFile {
        val active = if (file.storageFormat == StorageFormat.LEGACY_PLAIN) {
            migrate(db, file).also { onSwitch() }
        } else {
            if (file.legacyObjectKey == null) return file
            files.get(file)
            file
        }
        removeLegacy(db, active)
        return StoredFile(
            active.id, active.ownerId, active.originalName, active.sizeBytes, active.sha256, active.objectKey,
            active.status, active.createdAt, active.storageFormat, active.ciphertextSizeBytes,
        )
    }

    private fun migrate(db: JdbcTemplate, file: StoredFile): StoredFile {
        verifyLegacyKey(file, file.objectKey)
        val bytes = files.get(file)
        val target = StoredFile(
            file.id, file.ownerId, file.originalName, file.sizeBytes, file.sha256, encryptedObjectKey(file.id),
            file.status, file.createdAt, StorageFormat.AEAD_V1, file.sizeBytes + FileEncryption.OVERHEAD, file.objectKey,
        )
        files.put(target, bytes)
        files.get(target)
        val updated = db.update(
            """update stored_files set object_key=?, storage_format='AEAD_V1', ciphertext_size_bytes=?, legacy_object_key=?
                where id=? and status='READY' and storage_format='LEGACY_PLAIN' and object_key=?""",
            target.objectKey, target.ciphertextSizeBytes, file.objectKey, file.id, file.objectKey,
        )
        if (updated != 1) throw FileUnavailableException(stage = Stage.METADATA_READY, fileId = file.id)
        return target
    }

    private fun removeLegacy(db: JdbcTemplate, file: StoredFile) {
        val legacyKey = file.legacyObjectKey
        verifyLegacyKey(file, legacyKey)
        s3.delete(requireNotNull(legacyKey), file.id)
        val updated = db.update(
            "update stored_files set legacy_object_key=null where id=? and storage_format='AEAD_V1' and object_key=? and legacy_object_key=?",
            file.id, file.objectKey, legacyKey,
        )
        if (updated != 1) throw FileUnavailableException(stage = Stage.METADATA_READY, fileId = file.id)
    }

    private fun verifyLegacyKey(file: StoredFile, key: String?) {
        if (key != "files/${file.id}") throw FileUnavailableException(stage = Stage.FILE_INTEGRITY, fileId = file.id)
    }

    companion object {
        private const val BATCH_LOCK = "file-encryption-batch"
        private const val PENDING = "status='READY' and (storage_format='LEGACY_PLAIN' or legacy_object_key is not null)"
        private val fileIdRow = RowMapper { rs, _ -> rs.getObject(1, UUID::class.java) }
        private val fileRow = RowMapper { rs, _ ->
            StoredFile(
                rs.getObject("id", UUID::class.java), rs.getObject("owner_id", UUID::class.java),
                rs.getString("original_name"), rs.getLong("size_bytes"), rs.getString("sha256"),
                rs.getString("object_key"), rs.getString("status"), rs.getTimestamp("created_at").toInstant(),
                StorageFormat.valueOf(rs.getString("storage_format")),
                rs.getObject("ciphertext_size_bytes", Long::class.javaObjectType), rs.getString("legacy_object_key"),
            )
        }
    }
}
