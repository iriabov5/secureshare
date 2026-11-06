package dev.secureshare

import dev.secureshare.identity.*
import dev.secureshare.security.SecurityConfiguration
import dev.secureshare.web.AuthExceptionHandler
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.dao.DataIntegrityViolationException
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import org.mockito.Mockito.*

class CredentialsPolicyTest {
    @ParameterizedTest
    @ValueSource(strings = ["ab", "", " user", "user ", "пользователь", "user-name"])
    @DisplayName("Недопустимый логин отклоняется без удаления пробелов")
    fun invalidUsername(value: String) { assertNull(CredentialsPolicy.username(value)) }

    @Test
    @DisplayName("Логин нормализуется одинаково на нижней и верхней границах")
    fun usernameBoundaries() {
        assertEquals("abc", CredentialsPolicy.username("AbC"))
        assertEquals("ab_01", CredentialsPolicy.username("Ab_01"))
        assertEquals("a".repeat(64), CredentialsPolicy.username("A".repeat(64)))
        assertNull(CredentialsPolicy.username("a".repeat(65)))
        assertNull(CredentialsPolicy.username(null))
    }

    @Test
    @DisplayName("Пароль ограничивается code points и UTF-8 байтами, без усечения")
    fun passwordBoundaries() {
        assertFalse(CredentialsPolicy.passwordValid("a".repeat(11)))
        assertTrue(CredentialsPolicy.passwordValid("a".repeat(12)))
        assertTrue(CredentialsPolicy.passwordValid("a".repeat(64)))
        assertFalse(CredentialsPolicy.passwordValid("a".repeat(65)))
        assertTrue(CredentialsPolicy.passwordValid("я".repeat(36)))
        assertFalse(CredentialsPolicy.passwordValid("я".repeat(36) + "a"))
        assertTrue(CredentialsPolicy.passwordValid("😀".repeat(12)))
        assertFalse(CredentialsPolicy.passwordValid("😀".repeat(11)))
    }

    @Test
    @DisplayName("Bcrypt сохраняет соль и исходные пробелы, отказывает длинному паролю")
    fun passwordEncoder() {
        val encoder = SecurityConfiguration().passwordEncoder()
        val raw = "  Secret passphrase  "
        val first = requireNotNull(encoder.encode(raw))
        assertTrue(first.startsWith("{bcrypt}$2a$12$"))
        assertNotEquals(first, encoder.encode(raw))
        assertTrue(encoder.matches(raw, first))
        assertFalse(encoder.matches(raw.trim(), first))
        assertFalse(encoder.matches("short", first))
        assertFalse(encoder.matches(null, first))
        assertFalse(encoder.matches(raw, null))
        assertThrows(IllegalArgumentException::class.java) { encoder.encode(null) }
        assertThrows(IllegalArgumentException::class.java) { encoder.encode("short") }
        val unicode = "café passphrase"
        val hash = encoder.encode(unicode)
        assertTrue(encoder.matches(unicode, hash))
        assertFalse(encoder.matches(java.text.Normalizer.normalize(unicode, java.text.Normalizer.Form.NFD), hash))
    }

    @Test
    @DisplayName("Регистрация использует фиксированные часы и не сохраняет невалидные credentials")
    fun registrationPolicy() {
        val repository = mock(AccountRepository::class.java)
        val encoder = SecurityConfiguration().passwordEncoder()
        val now = Instant.parse("2026-11-05T12:00:00Z")
        val service = RegistrationService(repository, encoder, Clock.fixed(now, ZoneOffset.UTC))
        for ((name, password) in listOf(null to "valid password", "alice" to null, "alice" to "short")) {
            assertThrows(InvalidRegistrationException::class.java) { service.register(name, password) }
        }
        verifyNoInteractions(repository)
        service.register("Alice", "valid password")
        val account = org.mockito.ArgumentCaptor.forClass(Account::class.java)
        verify(repository).saveAndFlush(account.capture())
        assertEquals(now, account.value.createdAt)
        assertEquals("alice", account.value.username)
        assertEquals("USER", account.value.role)
    }

    @Test
    @DisplayName("Нарушение другого ограничения не выдаётся за занятый логин")
    fun unrelatedConstraint() {
        val handler = AuthExceptionHandler()
        assertEquals(503, handler.conflict(DataIntegrityViolationException("different constraint")).statusCode.value())
        val nested = DataIntegrityViolationException("insert failed", IllegalStateException("uq_app_users_username"))
        assertEquals(409, handler.conflict(nested).statusCode.value())
    }
}
