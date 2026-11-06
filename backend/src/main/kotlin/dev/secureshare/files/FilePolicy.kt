package dev.secureshare.files

import java.security.MessageDigest
import java.util.HexFormat

object FilePolicy {
    const val MAX_BYTES = 10 * 1024 * 1024

    fun validate(name: String?, size: Long): String {
        if (size > MAX_BYTES) throw FileTooLargeException()
        if (size < 1 || name.isNullOrBlank() || name == "." || name == ".." ||
            name.toByteArray(Charsets.UTF_8).size > 255 ||
            name.any { it == '/' || it == '\u005c' || it.isISOControl() }) throw InvalidFileException()
        return name
    }

    fun sha256(bytes: ByteArray): String = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))
}
