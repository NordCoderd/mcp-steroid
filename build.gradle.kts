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

dependencies {
    intellijPlatform {
        intellijIdeaUltimate("2025.3")
        bundledPlugin("com.intellij.mcpServer")
        testFramework(org.jetbrains.intellij.platform.gradle.TestFrameworkType.Platform)
    }

//    implementation("org.jetbrains.kotlinx:kotlinx-serialization-bom:1.9.0")
//    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json")
//    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-bom:1.10.2")
//    implementation("org.jetbrains.kotlinx:kotlinx-coroutines")
//    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test")

    // Testing
    testImplementation("junit:junit:4.13.2")
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
            untilBuild = "*"
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
