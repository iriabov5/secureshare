package dev.secureshare.identity

import org.springframework.security.crypto.password.PasswordEncoder
import java.util.Locale

object CredentialsPolicy {
    private val usernamePattern = Regex("[a-zA-Z0-9_]{3,64}")

    fun username(value: String?): String? =
        value?.takeIf { usernamePattern.matches(it) }?.lowercase(Locale.ROOT)

    /** Проверяет длину без изменения пароля и усечения UTF-8 перед bcrypt. */
    fun passwordValid(value: String): Boolean =
        value.codePointCount(0, value.length) in 12..64 && value.toByteArray(Charsets.UTF_8).size <= 72
}

class BoundedPasswordEncoder(private val delegate: PasswordEncoder) : PasswordEncoder {
    override fun encode(rawPassword: CharSequence?): String {
        require(rawPassword != null && CredentialsPolicy.passwordValid(rawPassword.toString())) { "Invalid password length" }
        return requireNotNull(delegate.encode(rawPassword))
    }

    override fun matches(rawPassword: CharSequence?, encodedPassword: String?): Boolean =
        rawPassword != null && encodedPassword != null && CredentialsPolicy.passwordValid(rawPassword.toString()) && delegate.matches(rawPassword, encodedPassword)
}
