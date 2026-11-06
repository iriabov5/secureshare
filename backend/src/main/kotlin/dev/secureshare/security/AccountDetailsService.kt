package dev.secureshare.security

import dev.secureshare.identity.AccountRepository
import dev.secureshare.identity.CredentialsPolicy
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.userdetails.User
import org.springframework.security.core.userdetails.UserDetails
import org.springframework.security.core.userdetails.UserDetailsService
import org.springframework.security.core.userdetails.UsernameNotFoundException
import org.springframework.stereotype.Service
import java.util.UUID

/** Хранит серверный UUID в principal; credentials очищает штатный ProviderManager. */
class AccountPrincipal(val accountId: UUID, username: String, hash: String) :
    User(username, hash, listOf(SimpleGrantedAuthority("ROLE_USER")))

@Service
class AccountDetailsService(private val accounts: AccountRepository) : UserDetailsService {
    /** Не различает для клиента неизвестный и некорректный логин. */
    override fun loadUserByUsername(username: String): UserDetails {
        val normalized = CredentialsPolicy.username(username) ?: throw UsernameNotFoundException("Invalid credentials")
        val account = accounts.findByUsername(normalized) ?: throw UsernameNotFoundException("Invalid credentials")
        return AccountPrincipal(account.id, account.username, account.passwordHash)
    }
}
