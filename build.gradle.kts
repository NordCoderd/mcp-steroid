import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.jetbrains.intellij.platform.gradle.extensions.IntelliJPlatformExtension
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.net.HttpURLConnection
import java.net.URI
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

plugins {
    id("org.jetbrains.intellij.platform") version "2.10.5"
    kotlin("jvm") version "2.2.21"
    kotlin("plugin.serialization") version "2.2.21"
}

group = "com.jonnyzzz.intellij"
version = "0.84.0-SNAPSHOT-${LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd-HH-mm"))}"

repositories {
    mavenCentral()

    intellijPlatform {
        defaultRepositories()
    }
}

val ktorVersion = "3.1.0"
val platformPathProvider = providers.provider {
    extensions.getByType<IntelliJPlatformExtension>().platformPath
}
val kotlinPluginPath = platformPathProvider.map { it.resolve("plugins/Kotlin") }
val javaPluginPath = platformPathProvider.map { it.resolve("plugins/java") }

configurations.named("intellijPlatformDependency").configure {
    incoming.afterResolve {
        val platformPath = extensions.getByType<IntelliJPlatformExtension>().platformPath
        val fullLineDescriptor = platformPath.resolve("plugins/fullLine/lib/modules/intellij.fullLine.yaml.jar")
        val backup = platformPath.resolve("plugins/fullLine/lib/modules/intellij.fullLine.yaml.jar.bak")
        if (Files.exists(fullLineDescriptor)) {
            // Work around a broken module descriptor that breaks plugin structure parsing.
            Files.move(fullLineDescriptor, backup, StandardCopyOption.REPLACE_EXISTING)
        }
    }
}

dependencies {
    intellijPlatform {
        intellijIdeaUltimate("2025.3")
        // Avoid bundled plugin scan warnings by pointing to the local Kotlin plugin path.
        localPlugin(kotlinPluginPath)
        localPlugin(javaPluginPath)
        testFramework(TestFrameworkType.Platform)
    }

    // Kotlin scripting for script definition provider
    compileOnly("org.jetbrains.kotlin:kotlin-scripting-common:2.2.21")
    compileOnly("org.jetbrains.kotlin:kotlin-scripting-jvm:2.2.21")

    // Ktor server for MCP HTTP transport
    implementation("io.ktor:ktor-server-core:$ktorVersion") {
        exclude(group = "org.jetbrains.kotlinx")
    }
    implementation("io.ktor:ktor-server-cio:$ktorVersion") {
        exclude(group = "org.jetbrains.kotlinx")
    }
    implementation("io.ktor:ktor-server-sse:$ktorVersion") {
        exclude(group = "org.jetbrains.kotlinx")
    }

    // Testing
    testImplementation("junit:junit:4.13.2")

    // https://mvnrepository.com/artifact/org.testcontainers/testcontainers-bom
    testImplementation(platform("org.testcontainers:testcontainers-bom:2.0.2"))
    testImplementation("org.testcontainers:testcontainers")

    // Ktor client for MCP SSE transport tests
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
    buildSearchableOptions = false
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
}


val deployPluginLocallyTo253 by tasks.registering(Sync::class) {
    dependsOn(tasks.buildPlugin)
    group = "intellij platform"
    outputs.upToDateWhen { false }

    val targetName = "" + rootProject.name
    val targetDir = "${System.getenv("HOME")}/intellij-253/config/plugins/$targetName"

    this.destinationDir = file(targetDir)
    from(
        tasks.buildPlugin
            .map { it.archiveFile }
            .map { zipTree(it) }
    ) {
        includeEmptyDirs = false
        eachFile {
            println(this)
            this.path = this.path.substringAfter("/")
        }
    }
}


// Deploy plugin to running IDEs with hot-reload support
val deployPlugin by tasks.registering {
    group = "intellij platform"
    description = "Deploy plugin to running IDEs"
    dependsOn(tasks.buildPlugin)
    doLast {
        val zip = tasks.buildPlugin.get().outputs.files.singleFile
        val home = File(System.getProperty("user.home"))
        val endpoints = home.listFiles { f -> f.name.matches(Regex("\\.\\d+\\.hot-reload")) }
            ?.mapNotNull { f ->
                val pid = Regex("\\.(\\d+)\\.").find(f.name)?.groupValues?.get(1)?.toLongOrNull() ?: return@mapNotNull null
                if (!ProcessHandle.of(pid).isPresent) return@mapNotNull null
                val lines = f.readLines().takeIf { it.size >= 2 } ?: return@mapNotNull null
                lines[0] to lines[1]
            }?.distinctBy { it.first } ?: emptyList()

        if (endpoints.isEmpty()) { println("No running IDEs found"); return@doLast }

        endpoints.forEach { (url, token) ->
            println("\n→ $url")
            val conn = (URI(url).toURL().openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"; doOutput = true
                setRequestProperty("Authorization", token)
                setRequestProperty("Content-Type", "application/octet-stream")
                connectTimeout = 5000; readTimeout = 300000
            }
            conn.outputStream.use { out -> zip.inputStream().use { it.copyTo(out) } }
            if (conn.responseCode in 200..299) {
                conn.inputStream.bufferedReader().forEachLine { println("  $it") }
            } else {
                println("  ✗ HTTP ${conn.responseCode}")
            }
        }
    }
}
