plugins {
    id("org.jetbrains.intellij.platform") version "2.10.5"
    kotlin("jvm") version "2.2.21"
    kotlin("plugin.serialization") version "2.2.21"
}

group = "com.jonnyzzz.intellij"
version = "1.0.0-SNAPSHOT"

repositories {
    mavenCentral()

    intellijPlatform {
        defaultRepositories()
    }
}

val ktorVersion = "3.0.3"

dependencies {
    intellijPlatform {
        intellijIdeaUltimate("2025.3")
        bundledPlugin("com.intellij.mcpServer")
        // Kotlin plugin for script engine support in tests
        bundledPlugin("org.jetbrains.kotlin")
        testFramework(org.jetbrains.intellij.platform.gradle.TestFrameworkType.Platform)
    }

    // Testing
    testImplementation("junit:junit:4.13.2")

    // https://mvnrepository.com/artifact/org.testcontainers/testcontainers-bom
    testImplementation(platform("org.testcontainers:testcontainers-bom:2.0.2"))
    testImplementation("org.testcontainers:testcontainers")

    // Ktor client for MCP SSE transport tests
    // Exclude kotlinx-coroutines and serialization to use IntelliJ's bundled versions
    testImplementation("io.ktor:ktor-client-core:$ktorVersion") {
        exclude(group = "org.jetbrains.kotlinx")
    }
    testImplementation("io.ktor:ktor-client-cio:$ktorVersion") {
        exclude(group = "org.jetbrains.kotlinx")
    }
    testImplementation("io.ktor:ktor-client-content-negotiation:$ktorVersion") {
        exclude(group = "org.jetbrains.kotlinx")
    }
    testImplementation("io.ktor:ktor-serialization-kotlinx-json:$ktorVersion") {
        exclude(group = "org.jetbrains.kotlinx")
    }
}

kotlin {
    jvmToolchain(21)
}

intellijPlatform {
    pluginConfiguration {
        name = "IntelliJ MCP Steroid"
        version = project.version.toString()

        ideaVersion {
            sinceBuild = "252.1"
            untilBuild = null
        }
    }
}

tasks {
    test {
        useJUnit()
    }

    processTestResources {
        duplicatesStrategy = DuplicatesStrategy.INCLUDE
    }
}
