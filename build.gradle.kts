import org.gradle.api.file.DuplicatesStrategy.INCLUDE
import org.gradle.kotlin.dsl.invoke

plugins {
    application
    kotlin("jvm") version "2.1.10"
    kotlin("plugin.serialization") version "2.1.10"
    id("io.ktor.plugin") version "3.0.3"
    id("org.jlleitschuh.gradle.ktlint") version "11.6.1"
    id("org.jlleitschuh.gradle.ktlint-idea") version "11.6.1"
    id("com.gradleup.shadow") version "9.2.2"
}

tasks {
    shadowJar {
        mergeServiceFiles()
        duplicatesStrategy = INCLUDE
        archiveFileName.set("app.jar")
    }
    test {
        useJUnitPlatform()
    }
    ktlintFormat {
        enabled = true
    }
    ktlintCheck {
        dependsOn("ktlintFormat")
    }
    build {
        dependsOn("ktlintCheck")
    }
/*
    withType<KotlinCompile>().all {
        compilerOptions {
            jvmTarget = JvmTarget.JVM_21
            freeCompilerArgs = listOf("-opt-in=kotlin.uuid.ExperimentalUuidApi")
        }
    }
 */
}

dependencies {
    implementation(libs.emottak.utils)
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.netty)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.hikari)
    implementation(libs.oracle)
    implementation(libs.bundles.exposed)
    implementation(libs.bundles.logging)
    implementation(libs.bundles.prometheus)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.cio)
    implementation(libs.jsch)
    implementation(libs.ktor.client.auth)
    implementation(libs.ktor.server.auth.jvm)
    implementation(libs.token.validation.ktor.v3)
    implementation("dev.reformator.stacktracedecoroutinator:stacktrace-decoroutinator-jvm:2.3.8")
    runtimeOnly("net.java.dev.jna:jna:5.12.1")
    testRuntimeOnly(testLibs.junit.jupiter.engine)
    testRuntimeOnly("com.h2database:h2:2.3.232")
    testImplementation(testLibs.mockk.jvm)
    testImplementation(testLibs.mockk.dsl.jvm)
    testImplementation(testLibs.junit.jupiter.api)
    testImplementation(testLibs.bundles.kotest)
    testImplementation(testLibs.ktor.server.test.host)
    testImplementation(kotlin("test"))
    implementation(kotlin("stdlib-jdk8"))
}

application {
    mainClass.set("no.nav.emottak.cpa.AppKt")
}
