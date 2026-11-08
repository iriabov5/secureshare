package dev.secureshare.files

import java.time.Instant

enum class IntegrityStatus { VERIFIED }

data class FileIntegrityResult(val status: IntegrityStatus, val checkedAt: Instant)
