package dev.secureshare.files.migration

import dev.secureshare.files.EncryptedFileStorage
import dev.secureshare.files.FileEncryptionConfiguration
import dev.secureshare.files.S3Configuration
import dev.secureshare.files.S3Storage
import org.springframework.boot.autoconfigure.ImportAutoConfiguration
import org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration
import org.springframework.boot.jdbc.autoconfigure.JdbcTemplateAutoConfiguration
import org.springframework.context.annotation.Import

@ImportAutoConfiguration(DataSourceAutoConfiguration::class, JdbcTemplateAutoConfiguration::class, FlywayAutoConfiguration::class)
@Import(S3Configuration::class, S3Storage::class, FileEncryptionConfiguration::class, EncryptedFileStorage::class, EncryptionMigration::class)
class FileMigrationConfiguration
