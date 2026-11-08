package dev.secureshare

import dev.secureshare.files.FileKeysets
import dev.secureshare.files.migration.EncryptionMigration
import dev.secureshare.files.migration.FileMigrationConfiguration
import org.springframework.boot.WebApplicationType
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.boot.runApplication
import java.nio.file.Path
import kotlin.system.exitProcess

@SpringBootApplication
class SecureShareApplication

fun main(args: Array<String>) {
    when (args.firstOrNull()) {
        "generate-file-key" -> exitProcess(generateFileKey(args))
        "migrate-file-encryption" -> exitProcess(migrateFileEncryption(args.drop(1).toTypedArray()))
        else -> runApplication<SecureShareApplication>(*args)
    }
}

private fun generateFileKey(args: Array<String>): Int {
    if (args.size != 2 || !args[1].endsWith(".tink-keyset")) {
        System.err.println("Использование: generate-file-key <путь к .tink-keyset>")
        return 2
    }
    return try {
        FileKeysets.generate(Path.of(args[1]))
        println("Ключ шифрования создан")
        0
    } catch (_: Exception) {
        System.err.println("Ключ не создан. Проверьте путь и права. Существующий файл не перезаписывается.")
        1
    }
}

private fun migrateFileEncryption(args: Array<String>): Int = try {
    SpringApplicationBuilder(FileMigrationConfiguration::class.java)
        .web(WebApplicationType.NONE).run(*args).use { context ->
            val result = context.getBean(EncryptionMigration::class.java).run()
            println("Перенесено: ${result.migrated}, очищено: ${result.cleaned}, осталось: ${result.remaining}")
            if (result.remaining == 0L) 0 else 1
        }
} catch (_: Exception) {
    System.err.println("Миграция прервана. Проверьте конфигурацию, доступность хранилищ и отсутствие другого запуска.")
    1
}
