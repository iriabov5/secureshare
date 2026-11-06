plugins {
    base
    kotlin("jvm") version "2.4.21" apply false
    kotlin("plugin.jpa") version "2.4.21" apply false
    id("org.springframework.boot") version "4.1.2" apply false
    id("io.spring.dependency-management") version "1.1.7" apply false
    id("org.sonarqube") version "7.5.0.8588"
    kotlin("plugin.spring") version "2.4.21"
}

val npmCommand = if (System.getProperty("os.name").startsWith("Windows")) "npm.cmd" else "npm"
val npmInstall = tasks.register<Exec>("npmInstall") {
    group = "build setup"
    description = "Устанавливает закреплённые npm зависимости."
    workingDir(rootDir)
    commandLine(npmCommand, "ci", "--no-audit", "--no-fund")
    inputs.files("package.json", "package-lock.json", "frontend/package.json")
    outputs.file("node_modules/.package-lock.json")
}
val frontendBuild = tasks.register<Exec>("frontendBuild") {
    group = "build"
    description = "Собирает React приложение."
    dependsOn(npmInstall)
    workingDir(rootDir)
    commandLine(npmCommand, "run", "build", "--workspace", "frontend")
    inputs.files(fileTree("frontend") { exclude("dist/**", "coverage/**", "node_modules/**", "test-results/**", "playwright-report/**", "**/*.tsbuildinfo") })
    inputs.file("package-lock.json")
    outputs.dir("frontend/dist")
}
val frontendCheck = tasks.register<Exec>("frontendCheck") {
    group = "verification"
    description = "Проверяет типы, стиль и тесты frontend."
    dependsOn(npmInstall)
    workingDir(rootDir)
    commandLine(npmCommand, "run", "check", "--workspace", "frontend")
}
tasks.named("check") { dependsOn(":backend:check", frontendCheck) }
tasks.named("assemble") { dependsOn(":backend:bootJar") }
tasks.register("bootJar") { group = "build"; description = "Собирает backend jar с React."; dependsOn(":backend:bootJar") }
tasks.named<Delete>("clean") { dependsOn(":backend:clean"); delete("frontend/coverage", "frontend/dist", "frontend/node_modules/.tmp") }

sonar {
    properties {
        property("sonar.projectKey", "local_secureshare")
        property("sonar.projectName", "SecureShare")
        property("sonar.scm.disabled", true)
        property("sonar.python.version", "3.14")
        property("sonar.sources", "frontend/src,frontend/vite.config.ts,frontend/playwright.config.ts,frontend/eslint.config.js,frontend/e2e/Dockerfile,scripts,compose.yaml,build.gradle.kts,settings.gradle.kts")
        property("sonar.tests", "frontend/tests,frontend/e2e/files.spec.ts")
        property("sonar.javascript.lcov.reportPaths", "frontend/coverage/lcov.info")
        property("sonar.exclusions", "**/build/**,**/.gradle/**,**/node_modules/**,**/dist/**,**/coverage/**,**/__pycache__/**,**/*.pyc")
    }
}
repositories {
    mavenCentral()
}
project(":backend") {
    sonar {
        properties {
            property("sonar.sources", "src/main/kotlin,src/main/resources,build.gradle.kts")
            property("sonar.coverage.jacoco.xmlReportPaths", "build/reports/jacoco/test/jacocoTestReport.xml")
        }
    }
}
