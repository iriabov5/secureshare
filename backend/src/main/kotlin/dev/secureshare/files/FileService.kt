package dev.secureshare.files

import dev.secureshare.files.migration.EncryptionMigration
import dev.secureshare.observability.AppLog
import dev.secureshare.observability.Outcome
import dev.secureshare.observability.Stage
import dev.secureshare.security.AccountPrincipal
import org.springframework.dao.DataAccessException
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Service
import org.springframework.transaction.TransactionException
import org.springframework.web.multipart.MultipartFile
import java.io.IOException
import java.time.Clock
import java.util.UUID

@Service
@PreAuthorize("isAuthenticated()")
class FileService(
    private val catalog: FileCatalog,
    private val files: StoredFileRepository,
    private val storage: ObjectStorage,
    private val clock: Clock,
    private val migration: EncryptionMigration,
) {
    fun upload(file: MultipartFile?): FileInfo {
        if (file == null) throw InvalidFileException()
        val name = FilePolicy.validate(file.originalFilename, file.size)
        val bytes = try { file.bytes } catch (exception: IOException) { throw FileUnavailableException(exception) }
        FilePolicy.validate(name, bytes.size.toLong())
        val owner = principal().accountId
        val record = metadata(Stage.METADATA_CREATE) { catalog.create(owner, name, bytes) }
        AppLog.event("file.upload.started", fields = mapOf("accountId" to owner, "fileId" to record.id, "sizeBytes" to bytes.size))
        try {
            storage.put(record, bytes)
        } catch (exception: FileUnavailableException) {
            // PUT мог завершиться до timeout: сохраняем ключ для сверки, не удаляем объект.
            try { catalog.failed(record.id) } catch (failure: DataAccessException) { exception.addSuppressed(failure) }
            catch (failure: TransactionException) { exception.addSuppressed(failure) }
            exception.fileId = record.id
            throw exception
        }
        metadata(Stage.METADATA_READY, record.id, Outcome.UNKNOWN) { catalog.ready(record.id) }
        AppLog.event("file.upload.succeeded", Outcome.SUCCESS, fields = mapOf("accountId" to owner, "fileId" to record.id, "sizeBytes" to bytes.size))
        return record.toInfo()
    }

    fun list(): List<FileInfo> {
        val owner = principal().accountId
        val result = metadata(Stage.METADATA_LIST) { files.findAllByOwnerIdAndStatusOrderByCreatedAtDescIdDesc(owner, "READY").map { it.toInfo() } }
        AppLog.event("file.list.succeeded", Outcome.SUCCESS, fields = mapOf("accountId" to owner, "resultCount" to result.size))
        return result
    }

    fun encrypt(id: UUID): FileInfo {
        val record = ownReadyFile(id)
        val encrypted = migration.encrypt(id, record.ownerId)
        AppLog.event("file.encryption.succeeded", Outcome.SUCCESS, fields = mapOf("accountId" to record.ownerId, "fileId" to id))
        return encrypted.toInfo()
    }

    fun download(id: UUID): FileDownload {
        val record = ownReadyFile(id)
        return FileDownload(record.originalName, readVerified(record))
    }

    fun checkIntegrity(id: UUID): FileIntegrityResult {
        val record = ownReadyFile(id)
        readVerified(record)
        val checkedAt = clock.instant()
        AppLog.event("file.integrity-check.succeeded", Outcome.SUCCESS, fields = mapOf("accountId" to record.ownerId, "fileId" to id))
        return FileIntegrityResult(IntegrityStatus.VERIFIED, checkedAt)
    }

    private fun readVerified(record: StoredFile): ByteArray =
        try {
            storage.get(record)
        } catch (failure: FileUnavailableException) {
            if (record.storageFormat != StorageFormat.LEGACY_PLAIN) throw failure
            val current = ownReadyFile(record.id)
            if (current.storageFormat != StorageFormat.AEAD_V1 || current.objectKey == record.objectKey) throw failure
            storage.get(current)
        }

    private fun ownReadyFile(id: UUID): StoredFile =
        metadata(Stage.METADATA_LOAD, id) { catalog.loadForDownload(id) }

    private fun <T> metadata(stage: Stage, id: UUID? = null, outcome: Outcome = Outcome.ERROR, action: () -> T): T =
        try { action() }
        catch (exception: FileUnavailableException) { throw FileUnavailableException(exception, stage, outcome, id) }
        catch (exception: DataAccessException) { throw FileUnavailableException(exception, stage, outcome, id) }
        catch (exception: TransactionException) { throw FileUnavailableException(exception, stage, outcome, id) }

    private fun principal() = requireNotNull(SecurityContextHolder.getContext().authentication).principal as AccountPrincipal
}
