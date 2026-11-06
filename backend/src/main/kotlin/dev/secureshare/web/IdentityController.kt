package dev.secureshare.web

import dev.secureshare.security.AccountPrincipal
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController

@RestController
class IdentityController {
    @GetMapping("/api/v1/me")
    fun me(@AuthenticationPrincipal principal: AccountPrincipal): CurrentIdentity = principal.toCurrentIdentity()
}
