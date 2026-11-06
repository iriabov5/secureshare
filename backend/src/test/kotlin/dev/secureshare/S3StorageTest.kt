package dev.secureshare

import dev.secureshare.files.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.mockito.Mockito.*
import software.amazon.awssdk.core.ResponseInputStream
import software.amazon.awssdk.core.exception.SdkClientException
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.GetObjectRequest
import software.amazon.awssdk.services.s3.model.GetObjectResponse
import software.amazon.awssdk.services.s3.model.PutObjectRequest
import software.amazon.awssdk.core.sync.RequestBody
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.time.Instant
import java.util.UUID

class S3StorageTest {
    @Test
    @DisplayName("S3 adapter проверяет размер и hash, закрывает поток и оборачивает IOException")
    fun boundedReads() {
        val client = mock(S3Client::class.java); val storage = S3Storage(client, S3Properties())
        val bytes = "hello".toByteArray()
        val file = StoredFile(UUID.randomUUID(), UUID.randomUUID(), "hello", 5, FilePolicy.sha256(bytes), "files/key", createdAt = Instant.EPOCH)
        for (content in listOf(bytes, "hell".toByteArray(), "longer".toByteArray(), "other".toByteArray())) {
            val stream = ResponseInputStream(GetObjectResponse.builder().build(), ByteArrayInputStream(content))
            `when`(client.getObject(any(GetObjectRequest::class.java))).thenReturn(stream)
            if (content.contentEquals(bytes)) assertArrayEquals(bytes, storage.get(file))
            else assertThrows(FileUnavailableException::class.java) { storage.get(file) }
        }
        val broken = object : InputStream() { override fun read(): Int = throw java.io.IOException("offline") }
        `when`(client.getObject(any(GetObjectRequest::class.java))).thenReturn(ResponseInputStream(GetObjectResponse.builder().build(), broken))
        assertThrows(FileUnavailableException::class.java) { storage.get(file) }
        `when`(client.getObject(any(GetObjectRequest::class.java))).thenThrow(SdkClientException.create("offline"))
        assertThrows(FileUnavailableException::class.java) { storage.get(file) }
    }

    @Test
    @DisplayName("S3 PUT оборачивает отказ SDK в безопасную доменную ошибку")
    fun putFailure() {
        val client = mock(S3Client::class.java); val storage = S3Storage(client, S3Properties())
        doThrow(SdkClientException.create("offline")).`when`(client).putObject(any(PutObjectRequest::class.java), any(RequestBody::class.java))
        assertThrows(FileUnavailableException::class.java) { storage.put("files/key", byteArrayOf(1)) }
    }
}
