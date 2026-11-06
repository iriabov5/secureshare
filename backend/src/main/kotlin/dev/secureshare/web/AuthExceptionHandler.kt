package dev.secureshare.web

import dev.secureshare.identity.InvalidRegistrationException
import dev.secureshare.observability.*
import org.slf4j.event.Level
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.dao.DataAccessException
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.transaction.TransactionException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

@RestControllerAdvice(assignableTypes = [AuthController::class])
@Order(Ordered.HIGHEST_PRECEDENCE)
class AuthExceptionHandler {
    @ExceptionHandler(InvalidRegistrationException::class, HttpMessageNotReadableException::class)
    fun invalid(): ResponseEntity<ApiError> {
        AppLog.event("account.registration.rejected", Outcome.REJECTED, Level.WARN, mapOf("reasonCode" to "VALIDATION_ERROR"))
        return ResponseEntity.badRequest().body(ApiError("VALIDATION_ERROR", "Проверьте логин и пароль"))
    }

    @ExceptionHandler(DataIntegrityViolationException::class)
    fun conflict(exception: DataIntegrityViolationException): ResponseEntity<ApiError> =
        if (generateSequence<Throwable>(exception) { it.cause }.any { it.message?.contains("uq_app_users_username") == true }) {
            AppLog.event("account.registration.rejected", Outcome.REJECTED, Level.WARN, mapOf("reasonCode" to "USERNAME_TAKEN"))
            ResponseEntity.status(409).body(ApiError("USERNAME_TAKEN", "Логин занят"))
        } else unavailable(exception)

    @ExceptionHandler(DataAccessException::class, TransactionException::class)
    fun unavailable(exception: Exception): ResponseEntity<ApiError> {
        AppLog.failure(Operation.REGISTRATION, exception, Stage.REGISTRATION_COMMIT)
        return ResponseEntity.status(503).body(ApiError("SERVICE_UNAVAILABLE", "Сервис временно недоступен"))
    }
}
