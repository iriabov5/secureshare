package dev.secureshare.web

import dev.secureshare.observability.*
import org.slf4j.event.Level
import jakarta.servlet.http.HttpServletRequest
import dev.secureshare.files.InvalidFileException
import dev.secureshare.files.FileTooLargeException
import dev.secureshare.files.FileUnavailableException
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.dao.DataAccessException
import org.springframework.http.ResponseEntity
import org.springframework.transaction.TransactionException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException

@RestControllerAdvice(assignableTypes = [FileApiController::class])
@Order(Ordered.HIGHEST_PRECEDENCE)
class FileExceptionHandler {
    @ExceptionHandler(InvalidFileException::class, FileTooLargeException::class, FileUnavailableException::class, DataAccessException::class, TransactionException::class, MethodArgumentTypeMismatchException::class)
    fun handle(exception: Exception, request: HttpServletRequest): ResponseEntity<ApiError> {
        val (status, error) = when (exception) {
            is InvalidFileException, is MethodArgumentTypeMismatchException -> 400 to ApiError("INVALID_FILE", "Некорректный файл или идентификатор")
            is FileTooLargeException -> 413 to ApiError("FILE_TOO_LARGE", "Файл превышает допустимый размер")
            else -> 503 to ApiError("FILE_SERVICE_UNAVAILABLE", "Файловый сервис временно недоступен")
        }
        if (status == 503) {
            val operation = when { request.method == "POST" -> Operation.FILE_UPLOAD; request.requestURI.endsWith("/content") -> Operation.FILE_DOWNLOAD; else -> Operation.FILE_LIST }
            AppLog.failure(operation, exception)
        } else {
            val event = if (request.method == "GET") "file.download.rejected" else "file.upload.rejected"
            AppLog.event(event, Outcome.REJECTED, Level.WARN, mapOf("reasonCode" to error.code))
        }
        return ResponseEntity.status(status).body(error)
    }
}
