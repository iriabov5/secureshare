package dev.secureshare.files

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.env.Environment
import org.springframework.core.env.Profiles
import org.springframework.stereotype.Component
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.core.checksums.RequestChecksumCalculation
import software.amazon.awssdk.core.checksums.ResponseChecksumValidation
import software.amazon.awssdk.core.exception.SdkException
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.retries.StandardRetryStrategy
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.GetObjectRequest
import software.amazon.awssdk.services.s3.model.PutObjectRequest
import dev.secureshare.observability.Stage
import dev.secureshare.observability.Outcome
import software.amazon.awssdk.core.exception.SdkClientException
import java.io.IOException
import java.net.URI
import java.time.Duration

@ConfigurationProperties("storage.s3")
class S3Properties(
    val endpoint: String = "",
    val region: String = "us-east-1",
    val bucket: String = "secureshare",
    val pathStyle: Boolean = false,
    val accessKey: String = "",
    val secretKey: String = "",
)

@Configuration
@EnableConfigurationProperties(S3Properties::class)
class S3Configuration {
    @Bean(destroyMethod = "close")
    fun s3Client(properties: S3Properties, environment: Environment): S3Client {
        require(properties.bucket.isNotBlank() && properties.region.isNotBlank()) { "S3 bucket and region are required" }
        require(properties.accessKey.isBlank() == properties.secretKey.isBlank()) { "Both S3 credential fields are required" }
        val credentials = if (properties.accessKey.isBlank()) DefaultCredentialsProvider.builder().build()
            else StaticCredentialsProvider.create(AwsBasicCredentials.create(properties.accessKey, properties.secretKey))
        val builder = S3Client.builder().region(Region.of(properties.region)).credentialsProvider(credentials)
            .forcePathStyle(properties.pathStyle)
            .httpClientBuilder(UrlConnectionHttpClient.builder().connectionTimeout(Duration.ofSeconds(2)).socketTimeout(Duration.ofSeconds(2)))
            .overrideConfiguration { it.apiCallTimeout(Duration.ofSeconds(5)).apiCallAttemptTimeout(Duration.ofSeconds(3)).retryStrategy(StandardRetryStrategy.builder().maxAttempts(1).build()) }
            .requestChecksumCalculation(RequestChecksumCalculation.WHEN_REQUIRED)
            .responseChecksumValidation(ResponseChecksumValidation.WHEN_REQUIRED)
        if (properties.endpoint.isNotBlank()) {
            val endpoint = URI.create(properties.endpoint)
            val localHttp = environment.acceptsProfiles(Profiles.of("local")) && endpoint.scheme == "http" &&
                endpoint.host in listOf("localhost", "127.0.0.1", "[::1]")
            require((endpoint.scheme == "https" || localHttp) && endpoint.host != null && endpoint.userInfo == null && endpoint.query == null && endpoint.fragment == null) { "S3 endpoint must use HTTPS; local loopback HTTP is allowed only in local profile" }
            builder.endpointOverride(endpoint)
        }
        return builder.build()
    }
}

@Component
class S3Storage(private val client: S3Client, private val properties: S3Properties) : ObjectStorage {
    override fun put(key: String, bytes: ByteArray) {
        try {
            client.putObject(PutObjectRequest.builder().bucket(properties.bucket).key(key).contentType("application/octet-stream").build(), RequestBody.fromBytes(bytes))
        } catch (exception: SdkException) {
            throw FileUnavailableException(exception, Stage.S3_PUT, if (exception is SdkClientException) Outcome.UNKNOWN else Outcome.ERROR)
        }
    }

    override fun get(file: StoredFile): ByteArray {
        try {
            client.getObject(GetObjectRequest.builder().bucket(properties.bucket).key(file.objectKey).build()).use { stream ->
                val bytes = stream.readNBytes(file.sizeBytes.toInt() + 1)
                if (bytes.size.toLong() != file.sizeBytes || FilePolicy.sha256(bytes) != file.sha256) throw FileUnavailableException(stage = Stage.FILE_INTEGRITY, fileId = file.id)
                return bytes
            }
        } catch (exception: SdkException) {
            throw FileUnavailableException(exception, Stage.S3_GET, fileId = file.id)
        } catch (exception: IOException) {
            throw FileUnavailableException(exception, Stage.S3_GET, fileId = file.id)
        }
    }
}
