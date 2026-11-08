package dev.secureshare

import java.util.UUID

internal object TestEndpoints {
    const val REGISTER = "/api/v1/auth/register"
    const val LOGIN = "/api/v1/auth/login"
    const val LOGOUT = "/api/v1/auth/logout"
    const val ME = "/api/v1/me"
    const val APP = "/app"
    const val FILES_PAGE = "/files"
    const val FILES_API = "/api/v1/files"
    const val UNLISTED_API = "/api/private"
    fun encryptFile(id: UUID): String = "$FILES_API/$id/encrypt"
    fun integrityCheck(id: UUID): String = "$FILES_API/$id/integrity-check"
    fun fileContent(id: UUID): String = "$FILES_API/$id/content"
}
