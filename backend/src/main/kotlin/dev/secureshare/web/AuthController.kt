package dev.secureshare.web

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import dev.secureshare.identity.RegistrationService
import dev.secureshare.observability.AppLog
import dev.secureshare.observability.Outcome
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.security.web.csrf.CsrfToken
import org.springframework.web.bind.annotation.*

@JsonIgnoreProperties(ignoreUnknown = true)
class RegistrationRequest(val username: String? = null, val password: String? = null)

data class RegistrationResult(val message: String)
data class CsrfResponse(val token: String, val headerName: String)

@RestController
@RequestMapping("/api/v1/auth")
class AuthController(private val registration: RegistrationService) {
    @GetMapping("/csrf")
    fun csrf(token: CsrfToken) = CsrfResponse(token.token, token.headerName)

    @PostMapping("/register", consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun register(@RequestBody request: RegistrationRequest): ResponseEntity<RegistrationResult> {
        val id = registration.register(request.username, request.password)
        AppLog.event("account.registration.succeeded", Outcome.SUCCESS, fields = mapOf("accountId" to id))
        return ResponseEntity.status(HttpStatus.CREATED).body(RegistrationResult("Аккаунт создан"))
    }
}
