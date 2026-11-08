package dev.secureshare.web

import dev.secureshare.observability.RequestLoggingFilter
import dev.secureshare.security.AccountPrincipal
import org.springframework.security.core.annotation.AuthenticationPrincipal
import jakarta.servlet.http.HttpServletRequest
import dev.secureshare.files.FileInfo
import dev.secureshare.files.FileService
import org.springframework.http.*
import org.springframework.web.bind.annotation.*
import org.springframework.web.multipart.MultipartFile
import java.net.URI
import java.nio.charset.StandardCharsets
import java.util.UUID

@RestController
@RequestMapping("/api/v1/files")
class FileApiController(private val files: FileService) {
    @PostMapping(consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
    fun upload(@RequestParam("file", required = false) file: MultipartFile?): ResponseEntity<FileInfo> {
        val info = files.upload(file)
        return ResponseEntity.created(URI.create("/api/v1/files/${info.id}/content")).body(info)
    }

    @GetMapping
    fun list() = files.list()

    @PostMapping("/{id}/encrypt")
    fun encrypt(@PathVariable id: UUID) =
        ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(files.encrypt(id))

    @PostMapping("/{id}/integrity-check")
    fun checkIntegrity(@PathVariable id: UUID) =
        ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(files.checkIntegrity(id))

    @GetMapping("/{id}/content")
    fun download(@PathVariable id: UUID, request: HttpServletRequest, @AuthenticationPrincipal principal: AccountPrincipal): ResponseEntity<ByteArray> {
        val result = files.download(id)
        request.setAttribute(RequestLoggingFilter.DOWNLOAD, mapOf("accountId" to principal.accountId, "fileId" to id, "sizeBytes" to result.bytes.size))
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_OCTET_STREAM)
            .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(result.name, StandardCharsets.UTF_8).build().toString())
            .contentLength(result.bytes.size.toLong()).body(result.bytes)
    }
}
