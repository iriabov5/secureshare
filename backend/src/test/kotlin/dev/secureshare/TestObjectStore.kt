package dev.secureshare

import org.springframework.test.context.DynamicPropertyRegistry
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import java.time.Duration

class S3Container : GenericContainer<S3Container>("chrislusf/seaweedfs:4.52") {
    init {
        // Docker restart должен сохранить endpoint уже созданного S3Client.
        val port = java.net.ServerSocket(0).use { it.localPort }
        addFixedExposedPort(port, 8333)
    }
}

open class TestS3Store {
    val access = java.util.UUID.randomUUID().toString()
    val secret = java.util.UUID.randomUUID().toString()
    val bucket = "secureshare-tests"
    val container = S3Container().apply {
        withEnv("AWS_ACCESS_KEY_ID", access)
        withEnv("AWS_SECRET_ACCESS_KEY", secret)
        withEnv("S3_BUCKET", bucket)
        withExposedPorts(8333)
        withCommand("mini", "-dir=/data")
        waitingFor(Wait.forHttp("/").forPort(8333).forStatusCode(403).withStartupTimeout(Duration.ofSeconds(90)))
        start()
    }
    fun endpoint() = "http://${container.host}:${container.getMappedPort(8333)}"
    fun properties(registry: DynamicPropertyRegistry) {
        registry.add("storage.s3.endpoint", ::endpoint)
        registry.add("storage.s3.bucket") { bucket }
        registry.add("storage.s3.access-key") { access }
        registry.add("storage.s3.secret-key") { secret }
        registry.add("storage.s3.path-style") { true }
    }
}

object TestObjectStore : TestS3Store()
