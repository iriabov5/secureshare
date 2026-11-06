package dev.secureshare.web

import dev.secureshare.security.AccountPrincipal
import java.util.UUID

data class CurrentIdentity(val id: UUID, val username: String, val roles: List<String>)

fun AccountPrincipal.toCurrentIdentity() = CurrentIdentity(accountId, username, listOf("USER"))
