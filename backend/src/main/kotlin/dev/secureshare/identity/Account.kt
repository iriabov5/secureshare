package dev.secureshare.identity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.springframework.data.jpa.repository.JpaRepository
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "app_users")
class Account(
    @Id val id: UUID,
    @Column(nullable = false, unique = true, length = 64) val username: String,
    @Column(name = "password_hash", nullable = false, length = 255) val passwordHash: String,
    @Column(nullable = false, length = 16) val role: String = "USER",
    @Column(name = "created_at", nullable = false) val createdAt: Instant,
)

interface AccountRepository : JpaRepository<Account, UUID> {
    fun findByUsername(username: String): Account?
}
