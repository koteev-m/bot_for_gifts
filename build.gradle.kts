import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    application
    alias(libs.plugins.ktlint)
    alias(libs.plugins.detekt)
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

    implementation(libs.dotenv.kotlin)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.mockk)
    testRuntimeOnly(libs.junit.platform.launcher)
}

application {
    mainClass.set("com.example.app.ApplicationKt")
}

tasks.test {
    useJUnitPlatform()
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
