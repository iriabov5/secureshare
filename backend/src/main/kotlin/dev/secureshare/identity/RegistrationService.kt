package dev.secureshare.identity

import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant
import java.util.UUID

/** Атомарно создаёт пользователя; конфликт unique обрабатывается после отката. */
@Service
class RegistrationService(
    private val accounts: AccountRepository,
    private val encoder: PasswordEncoder,
    private val clock: Clock,
) {
    @Transactional
    fun register(username: String?, password: String?): UUID {
        val normalized = CredentialsPolicy.username(username) ?: throw InvalidRegistrationException()
        if (password == null || !CredentialsPolicy.passwordValid(password)) throw InvalidRegistrationException()
        val account = Account(UUID.randomUUID(), normalized, requireNotNull(encoder.encode(password)), createdAt = Instant.now(clock))
        accounts.saveAndFlush(account)
        return account.id
    }
}
