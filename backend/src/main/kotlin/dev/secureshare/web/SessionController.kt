package dev.secureshare.web

import dev.secureshare.observability.AppLog
import dev.secureshare.observability.Outcome
import dev.secureshare.security.AccountPrincipal
import dev.secureshare.sessions.ActiveSessions
import dev.secureshare.sessions.CurrentSessionException
import dev.secureshare.sessions.SessionNotFoundException
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException
import java.util.UUID

@RestController
class SessionController(private val sessions: ActiveSessions) {
    @GetMapping("/api/v1/me/sessions")
    fun list(@AuthenticationPrincipal account: AccountPrincipal, request: HttpServletRequest) =
        sessions.list(account, request.getSession(false)!!.id)

    @DeleteMapping("/api/v1/me/sessions/{id}")
    fun revoke(@AuthenticationPrincipal account: AccountPrincipal, request: HttpServletRequest, @PathVariable id: UUID): ResponseEntity<Void> {
        val target = sessions.revoke(account, request.getSession(false)!!.id, id)
        AppLog.event("auth.session.revoked", Outcome.SUCCESS, fields = mapOf("accountId" to account.accountId, "targetSessionId" to target.id))
        return ResponseEntity.noContent().build()
    }
}

@RestControllerAdvice(assignableTypes = [SessionController::class])
class SessionExceptionHandler {
    @ExceptionHandler(SessionNotFoundException::class, CurrentSessionException::class, MethodArgumentTypeMismatchException::class)
    fun handle(exception: Exception): ResponseEntity<ApiError> {
        val (status, error) = when (exception) {
            is SessionNotFoundException -> 404 to ApiError("SESSION_NOT_FOUND", "Сеанс не найден или уже завершён")
            is CurrentSessionException -> 409 to ApiError("CURRENT_SESSION", "Для текущего сеанса используйте выход")
            else -> 400 to ApiError("VALIDATION_ERROR", "Некорректный идентификатор сеанса")
        }
        return ResponseEntity.status(status).body(error)
    }
}
