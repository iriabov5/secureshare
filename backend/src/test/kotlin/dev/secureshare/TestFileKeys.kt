package dev.secureshare

import dev.secureshare.files.FileKeysets
import org.springframework.test.context.DynamicPropertyRegistry
import java.nio.file.Files

object TestFileKeys {
    private val file by lazy {
        val directory = Files.createTempDirectory("secureshare-keyset-")
        directory.toFile().deleteOnExit()
        directory.resolve("files.tink-keyset").also {
            FileKeysets.generate(it)
            it.toFile().deleteOnExit()
        }
    }

    fun properties(registry: DynamicPropertyRegistry) {
        registry.add("storage.encryption.keyset-path") { file.toString() }
    }
}
