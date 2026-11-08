package dev.secureshare.observability

import dev.secureshare.files.FileUnavailableException
import org.slf4j.LoggerFactory
import org.slf4j.event.Level
import java.util.UUID

enum class Outcome { SUCCESS, REJECTED, ERROR, UNKNOWN, CLIENT_DISCONNECTED }
enum class Stage { USER_LOOKUP, REGISTRATION_COMMIT, METADATA_CREATE, METADATA_LIST, METADATA_LOAD, METADATA_READY, S3_PUT, S3_GET, S3_DELETE, FILE_ENCRYPTION, FILE_INTEGRITY, UNEXPECTED }
enum class Operation { REGISTRATION, LOGIN, FILE_UPLOAD, FILE_LIST, FILE_DOWNLOAD, FILE_INTEGRITY_CHECK, FILE_MIGRATION, HTTP }

object AppLog {
    private val logger = LoggerFactory.getLogger("dev.secureshare.operations")

    fun event(name: String, outcome: Outcome? = null, level: Level = Level.INFO, fields: Map<String, Any> = emptyMap()) {
        val entry = logger.atLevel(level).addKeyValue("event", name)
        if (outcome != null) entry.addKeyValue("outcome", outcome.name)
        fields.forEach { (key, value) -> entry.addKeyValue(key, value) }
        entry.log("Событие приложения")
    }

    fun failure(operation: Operation, exception: Throwable, stage: Stage = Stage.UNEXPECTED, fileId: UUID? = null) {
        val context = exception as? FileUnavailableException
        val actualStage = context?.stage ?: stage
        val fields = linkedMapOf<String, Any>(
            "operation" to operation.name,
            "stage" to actualStage.name,
            "reasonCode" to reason(exception, actualStage),
            "error.type" to exception.javaClass.name,
            "rootCauseType" to generateSequence(exception) { it.cause }.last().javaClass.name,
        )
        when (actualStage) {
            Stage.S3_PUT, Stage.S3_GET, Stage.S3_DELETE, Stage.FILE_INTEGRITY -> fields["dependency"] = "S3"
            Stage.USER_LOOKUP, Stage.REGISTRATION_COMMIT, Stage.METADATA_CREATE, Stage.METADATA_LIST, Stage.METADATA_LOAD, Stage.METADATA_READY -> fields["dependency"] = "POSTGRES"
            Stage.FILE_ENCRYPTION, Stage.UNEXPECTED -> Unit
        }
        (context?.fileId ?: fileId)?.let { fields["fileId"] = it }
        if (exception.suppressed.isNotEmpty()) fields["cleanupOutcome"] = "FAILED"
        // Сообщения SQL/SDK и Throwable.toString могут содержать данные запроса и credentials.
        fields["stackFrames"] = exception.stackTrace.take(12).joinToString(";") { "${it.className}.${it.methodName}:${it.lineNumber}" }
        event("operation.failed", context?.outcome ?: Outcome.ERROR, Level.ERROR, fields)
    }
    private fun reason(exception: Throwable, stage: Stage): String {
        if (stage == Stage.FILE_ENCRYPTION) return "FILE_ENCRYPTION_FAILED"
        if (stage == Stage.FILE_INTEGRITY) return "FILE_INTEGRITY_MISMATCH"
        val causes = generateSequence(exception) { it.cause }.toList()
        if (causes.any { it is java.net.SocketTimeoutException || it is java.util.concurrent.TimeoutException ||
                it is software.amazon.awssdk.core.exception.ApiCallTimeoutException || it is software.amazon.awssdk.core.exception.ApiCallAttemptTimeoutException }) return "DEPENDENCY_TIMEOUT"
        if (causes.any { it is java.net.ConnectException }) return "DEPENDENCY_CONNECTION_FAILED"
        val sql = causes.filterIsInstance<java.sql.SQLException>().firstOrNull()
        if (sql != null) return when (sql.sqlState?.take(2)) {
            "08" -> "DEPENDENCY_CONNECTION_FAILED"
            "28" -> "DEPENDENCY_AUTHENTICATION_FAILED"
            "23" -> "DATA_CONSTRAINT_FAILED"
            else -> "DEPENDENCY_FAILURE"
        }
        val s3 = causes.filterIsInstance<software.amazon.awssdk.services.s3.model.S3Exception>().firstOrNull()
        if (s3 != null) return when (s3.statusCode()) {
            403 -> "STORAGE_ACCESS_DENIED"
            404 -> "STORAGE_OBJECT_MISSING"
            else -> "DEPENDENCY_FAILURE"
        }
        if ((exception as? FileUnavailableException)?.outcome == Outcome.UNKNOWN && stage == Stage.METADATA_READY) return "COMMIT_UNCONFIRMED"
        return if (stage == Stage.UNEXPECTED) "UNEXPECTED_ERROR" else "DEPENDENCY_FAILURE"
    }

}
