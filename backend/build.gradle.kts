import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm")
    kotlin("plugin.spring")
    kotlin("plugin.jpa")
    id("org.springframework.boot")
    id("io.spring.dependency-management")
    jacoco
}

group = "dev.secureshare"
version = "0.1.0"
extra["kotlin.version"] = "2.4.21"
repositories { mavenCentral() }
kotlin { jvmToolchain(25); compilerOptions { jvmTarget.set(JvmTarget.JVM_25); freeCompilerArgs.add("-Xjspecify-annotations=strict") } }
allOpen { annotation("jakarta.persistence.Entity") }

dependencies {
    implementation(platform("software.amazon.awssdk:bom:2.57.9"))
    implementation("software.amazon.awssdk:s3")
    implementation("software.amazon.awssdk:url-connection-client")
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation(kotlin("stdlib"))
    implementation("org.jetbrains.kotlin:kotlin-reflect")
    implementation("tools.jackson.module:jackson-module-kotlin")
    implementation("org.springframework.boot:spring-boot-starter-flyway")
    runtimeOnly("org.flywaydb:flyway-database-postgresql")
    runtimeOnly("org.postgresql:postgresql")
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-starter-security-test")
    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    testImplementation("org.testcontainers:testcontainers-junit-jupiter")
    testImplementation("org.testcontainers:testcontainers-postgresql")
    testImplementation("org.yaml:snakeyaml")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

jacoco { toolVersion = "0.8.16" }
tasks.test {
    useJUnitPlatform()
    maxHeapSize = "768m"
    systemProperty("spring.test.context.cache.maxSize", "4")
    testLogging { events("failed", "skipped"); exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.SHORT }
    finalizedBy(tasks.jacocoTestReport)
}
tasks.jacocoTestReport {
    dependsOn(tasks.test)
    reports { xml.required.set(true); html.required.set(true) }
}
tasks.jacocoTestCoverageVerification {
    dependsOn(tasks.jacocoTestReport)
    violationRules {
        rule {
            limit { counter = "LINE"; minimum = "0.80".toBigDecimal() }
            limit { counter = "BRANCH"; minimum = "0.80".toBigDecimal() }
        }
    }
}
tasks.check { dependsOn(tasks.jacocoTestCoverageVerification) }

dependencyLocking { lockAllConfigurations() }

tasks.bootJar {
    dependsOn(":frontendBuild")
    from(rootProject.file("frontend/dist")) { into("BOOT-INF/classes/static") }
}

tasks.processTestResources {
    dependsOn(":frontendBuild")
    from(rootProject.file("frontend/dist")) { into("static") }
}
