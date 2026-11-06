package dev.secureshare.files

import dev.secureshare.observability.Outcome
import dev.secureshare.observability.Stage
import java.util.UUID

class FileUnavailableException(
    cause: Throwable? = null,
    val stage: Stage = Stage.UNEXPECTED,
    val outcome: Outcome = Outcome.ERROR,
    fileId: UUID? = null,
) : RuntimeException("File service unavailable", cause) {
    var fileId: UUID? = fileId
        internal set
}
