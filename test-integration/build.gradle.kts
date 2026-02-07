@file:Suppress("HasPlatformType")

import de.undercouch.gradle.tasks.download.Download
import org.gradle.kotlin.dsl.provideDelegate

plugins {
    kotlin("jvm") version "2.2.21"
    id("de.undercouch.download") version "5.6.0"
}

group = "com.jonnyzzz.intellij"
version = rootProject.version

repositories {
    mavenCentral()
}

// Resolvable configuration to get the plugin .zip from root project
val pluginZip by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
    attributes {
        attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage::class, "plugin-zip"))
    }
}

dependencies {
    pluginZip(project(":"))

    testImplementation(project(":test-helper"))
    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter-api")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

kotlin {
    jvmToolchain(21)
}

val ideDownloadDir = layout.buildDirectory.dir("ide-download")
val (ideUrl, ideFileName) = run {
    val ideVersion = "2025.3.2"
    val ideUrlARM = "https://download.jetbrains.com/idea/idea-${ideVersion}-aarch64.tar.gz"
    val ideUrlX86 = "https://download.jetbrains.com/idea/idea-${ideVersion}.tar.gz"
    if (System.getProperty("os.arch").let { it == "aarch64" || it == "arm64" }) {
        ideUrlARM to "ideaIC-arm.tar.gz"
    } else {
        ideUrlX86 to "ideaIC-arm.tar.gz"
    }
}

val downloadIdea by tasks.registering(Download::class) {
    group = "ide"
    description = "Download IntelliJ IDEA"
    src(ideUrl)
    dest(ideDownloadDir.map { it.file(ideFileName) })
    onlyIfModified(true)
}

tasks.test {
    useJUnitPlatform()
    systemProperty("junit.jupiter.execution.timeout.default", "15m")

    dependsOn(pluginZip)
    dependsOn(downloadIdea)
    doFirst {
        val testOut = layout.buildDirectory.dir("test-logs").get().asFile.absolutePath
        mkdir(testOut)

        systemProperty("test.integration.plugin.zip", pluginZip.singleFile.absolutePath)
        systemProperty("test.integration.idea.archive", downloadIdea.get().dest.absolutePath)
        systemProperty("test.integration.docker", layout.projectDirectory.dir("src/test/docker").asFile.absolutePath)
        systemProperty("test.integration.testOutput", testOut)
    }
}
