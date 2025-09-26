import org.flywaydb.gradle.task.AbstractFlywayTask
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    application
    alias(libs.plugins.ktlint)
    alias(libs.plugins.detekt)
    alias(libs.plugins.flyway)
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(21))
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_21)
    }
}

repositories {
    mavenCentral()
}

dependencies {
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.netty)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.ktor.server.call.id)
    implementation(libs.ktor.server.call.logging)
    implementation(libs.ktor.server.metrics.micrometer)
    implementation(libs.ktor.server.default.headers)
    implementation(libs.ktor.server.conditional.headers)
    implementation(libs.ktor.server.double.receive)
    implementation(libs.ktor.server.body.limit)
    implementation(libs.ktor.server.status.pages)

    implementation(libs.ktor.client.cio)
    implementation(libs.ktor.client.content.negotiation)

    implementation(libs.kotlinx.coroutines)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.slf4j.api)
    implementation(libs.logback.classic)

    implementation(platform(libs.micrometer.bom))
    implementation(libs.micrometer.core)
    implementation(libs.micrometer.registry.prometheus)

    implementation(libs.hikaricp)
    implementation(libs.postgresql)
    implementation(libs.jdbi.core)
    implementation(libs.jdbi.sqlobject)
    implementation(libs.jdbi.kotlin)
    implementation(libs.jdbi.kotlin.sqlobject)

    implementation(libs.dotenv.kotlin)
    implementation(libs.snakeyaml)
    implementation(libs.kaml)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.mockk)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.ktor.server.test.host)
    testRuntimeOnly(libs.junit.platform.launcher)
}

val flywayUrl = System.getenv("DATABASE_URL")?.takeUnless { it.isBlank() }
val flywayUser = System.getenv("DATABASE_USER")?.takeUnless { it.isBlank() }
val flywayPassword = System.getenv("DATABASE_PASSWORD")?.takeUnless { it.isBlank() }

flyway {
    locations = arrayOf("filesystem:src/main/resources/db/migration")
    if (flywayUrl != null) {
        url = flywayUrl
    }
    if (flywayUser != null) {
        user = flywayUser
    }
    if (flywayPassword != null) {
        password = flywayPassword
    }
}

application {
    mainClass.set("com.example.app.ApplicationKt")
}

tasks.test {
    useJUnitPlatform()
    environment("FAIRNESS_KEY", "00112233445566778899aabbccddeeff00112233445566778899aabbccddeeff")
}

ktlint {
    verbose.set(true)
    outputToConsole.set(true)
}

detekt {
    buildUponDefaultConfig = true
    allRules = false
    config.setFrom(files("config/detekt.yml"))
}

tasks.register("formatKotlin") {
    group = "formatting"
    description = "Formats Kotlin sources with ktlint."
    dependsOn("ktlintFormat")
}

tasks.register("lintKotlin") {
    group = "verification"
    description = "Runs ktlint checks."
    dependsOn("ktlintCheck")
}

tasks.register("formatAll") {
    group = "formatting"
    description = "Runs all formatters."
    dependsOn("ktlintFormat")
}

tasks.register("staticCheck") {
    group = "verification"
    description = "Executes static analysis and tests."
    dependsOn("ktlintCheck", "detekt", "test")
}

tasks.register("verifyAll") {
    group = "verification"
    description = "Runs code style checks, static analysis, and tests."
    dependsOn("ktlintCheck", "detekt", "test")
}

tasks.withType<AbstractFlywayTask>().configureEach {
    onlyIf {
        if (flywayUrl == null) {
            logger.lifecycle("Skipping Flyway task $name because DATABASE_URL is not configured.")
            return@onlyIf false
        }
        true
    }
}
