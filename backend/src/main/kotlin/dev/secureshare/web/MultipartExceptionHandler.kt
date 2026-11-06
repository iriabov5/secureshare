package dev.secureshare.web

import dev.secureshare.observability.AppLog
import dev.secureshare.observability.Outcome
import org.slf4j.event.Level
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.multipart.MaxUploadSizeExceededException

// Multipart limit возникает до выбора controller, поэтому advice глобальный.
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
class MultipartExceptionHandler {
    @ExceptionHandler(MaxUploadSizeExceededException::class)
    fun tooLarge(): ResponseEntity<ApiError> {
        AppLog.event("file.upload.rejected", Outcome.REJECTED, Level.WARN, mapOf("reasonCode" to "FILE_TOO_LARGE"))
        return ResponseEntity.status(413).body(ApiError("FILE_TOO_LARGE", "Файл превышает допустимый размер"))
    }
}
