@file:Suppress("HasPlatformType")

import com.jonnyzzz.mcpSteroid.gradle.CompilePromptsTask
import com.jonnyzzz.mcpSteroid.gradle.GenerateMetadataTask
import de.undercouch.gradle.tasks.download.Download
import org.gradle.api.tasks.testing.Test
import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import java.net.HttpURLConnection
import java.net.URI
import java.security.MessageDigest
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.zip.ZipFile

plugins {
    id("de.undercouch.download") version "5.6.0"
    id("org.jetbrains.intellij.platform") version "2.10.5"
    kotlin("jvm") version "2.2.21"
    kotlin("plugin.serialization") version "2.2.21"
}

group = "com.jonnyzzz.intellij"
val baseVersion = file("VERSION").readText().trim()
val gitHash = providers.exec {
    commandLine("git", "rev-parse", "--short", "HEAD")
}.standardOutput.asText.get().trim()
version = "$baseVersion-SNAPSHOT-${LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd-HH-mm"))}-$gitHash"


repositories {
    mavenCentral()

    intellijPlatform {
        defaultRepositories()
    }
}

// Libraries provided by IntelliJ platform - exclude from bundling
configurations.named("implementation") {
    exclude(group = "org.jetbrains.kotlin")
    exclude(group = "org.jetbrains.kotlinx")
    exclude(group = "org.jetbrains", module = "annotations")
    exclude(group = "org.slf4j")
}

dependencies {
    intellijPlatform {
        intellijIdeaUltimate("2025.3")
        bundledPlugin("org.jetbrains.kotlin")
        testFramework(TestFrameworkType.Platform)
    }

    // OCR common models shared with ocr-tesseract CLI
    implementation(project(":ocr-common"))

    // AI agent MCP server configuration helpers
    implementation(project(":ai-agents"))

    // Ktor server for MCP HTTP transport
    val ktorVersion = "3.1.0"
    implementation("io.ktor:ktor-server-core:$ktorVersion")
    implementation("io.ktor:ktor-server-cio:$ktorVersion")
    implementation("io.ktor:ktor-server-sse:$ktorVersion")

    // PostHog analytics
    implementation("com.posthog:posthog-server:2.3.0")

    // Testing
    testImplementation("junit:junit:4.13.2")
    testImplementation(project(":test-helper"))

    // https://mvnrepository.com/artifact/org.testcontainers/testcontainers-bom
    testImplementation(platform("org.testcontainers:testcontainers-bom:2.0.2"))
    testImplementation("org.testcontainers:testcontainers")

    // Ktor client for MCP SSE transport tests
    testImplementation("io.ktor:ktor-client-core:$ktorVersion")
    testImplementation("io.ktor:ktor-client-cio:$ktorVersion")
    testImplementation("io.ktor:ktor-client-content-negotiation:$ktorVersion")
    testImplementation("io.ktor:ktor-serialization-kotlinx-json:$ktorVersion")
}

kotlin {
    jvmToolchain(21)
}

val generatedSourcesPath = layout.buildDirectory.dir("generated/kotlin")
val generatedTestSourcesPath = layout.buildDirectory.dir("generated/kotlin-test")


val compilePrompts by tasks.registering(CompilePromptsTask::class) {
    inputDir.set(layout.projectDirectory.dir("src/main/prompts"))
    outputDir.set(generatedSourcesPath.map { it.dir("prompts") })
    testOutputDir.set(generatedTestSourcesPath.map { it.dir("prompts") })
}

// Generate metadata with encoded version
val generateMetadata by tasks.registering(GenerateMetadataTask::class) {
    group = "build"
    description = "Generate plugin metadata with encoded version"

    versionString.set(version.toString())
    outputFile.set(generatedSourcesPath.map { it.file("PluginMetadata.kt") })

    outputs.upToDateWhen { false }
}

// Add generated sources to main source set and make kotlin compilation depend on it
kotlin.sourceSets.main {
    kotlin.srcDir(generatedSourcesPath)
}
kotlin.sourceSets.test {
    kotlin.srcDir(generatedTestSourcesPath)
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    dependsOn(generateMetadata)
    dependsOn(compilePrompts)
}

intellijPlatform {
    caching {
        ides {
            enabled = true
        }
    }
    buildSearchableOptions = false
    pluginConfiguration {
        name = "MCP Steroid"
        version = project.version.toString()

        ideaVersion {
            sinceBuild = "253"
            untilBuild = null
        }
    }
}

tasks {
    test {
        useJUnit()
    }
}

allprojects {
    tasks.withType<Test>().configureEach {
        systemProperty("mcp.steroid.test.projectHome", rootProject.layout.projectDirectory.asFile.absolutePath)
        maxHeapSize = "4g"
    }
}

val kotlincVersion = "2.2.21"
val kotlincUrl = "https://github.com/JetBrains/kotlin/releases/download/v${kotlincVersion}/kotlin-compiler-${kotlincVersion}.zip"
val kotlincSha256Url = "$kotlincUrl.sha256"
val kotlincDownloadDir = layout.buildDirectory.dir("kotlinc-zip")
val kotlincDir = layout.buildDirectory.dir("kotlinc-unpack")

val downloadKotlinc by tasks.registering {
    group = "kotlinc"
    outputs.dir(kotlincDir)

    doLast {
        val files = fileTree(kotlincDownloadDir).files
        val sha256 = files
            .single { it.name.endsWith(".sha256") }
            .readText()

        val zip = files.single { it.name.endsWith(".zip") }

        val actualSha256 = MessageDigest.getInstance("SHA-256").run {
            update(zip.readBytes())
            digest().toHexString()
        }

        assert(actualSha256 == sha256) {
            "Actual:\n${actualSha256}\nExpected\n${sha256}"
        }

        sync {
            into(kotlincDir)
            from(zipTree(zip))
        }
    }
}

listOf(kotlincUrl, kotlincSha256Url).forEach { url ->
    val task = tasks.register<Download>("downloadKotlinc_" + url.substringAfterLast(".")) {
        group = "kotlinc"
        src(url)
        dest(kotlincDownloadDir)
        onlyIfModified(true)
    }
    downloadKotlinc.configure { dependsOn(task) }
}

val ocrToolDist by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
    attributes {
        attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage::class, "install-dist"))
    }
}

dependencies {
    ocrToolDist(project(":ocr-tesseract"))
}

listOf(tasks.prepareSandbox, tasks.prepareTestSandbox).forEach {
    it.invoke {
        from(ocrToolDist) {
            into(intellijPlatform.projectName.map { "$it/ocr-tesseract" })
        }
        from(downloadKotlinc) {
            into(intellijPlatform.projectName)
        }
        // Include LICENSE file in plugin root
        from(layout.projectDirectory.file("website/static/LICENSE")) {
            into(intellijPlatform.projectName)
        }
    }
}

// Expose plugin .zip for consumption by test-integration module
val pluginZipElements by configurations.creating {
    isCanBeConsumed = true
    isCanBeResolved = false
    attributes {
        attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage::class, "plugin-zip"))
    }
}

tasks.buildPlugin {
    doFirst {
        val outputDir = tasks.buildPlugin.get().archiveFile.get().asFile.parentFile
        val zips = outputDir
            .listFiles()
            ?.filter { it.name.endsWith(".zip") }
            ?.filter { file -> file.lastModified() < System.currentTimeMillis() - 1000L * 60 * 60 * 12 }
            ?: return@doFirst

        delete(zips)
    }
}

artifacts {
    add(pluginZipElements.name, tasks.buildPlugin.map { it.archiveFile }) {
        builtBy(tasks.buildPlugin)
    }
}

// Verify bundled libraries in plugin/lib folder
val verifyBundledLibraries by tasks.registering {
    group = "verification"
    description = "List and verify libraries bundled in plugin lib folder"
    dependsOn(tasks.buildPlugin)
    doLast {
        val zip = tasks.buildPlugin.get().outputs.files.singleFile
        var allFiles = ZipFile(zip).use {
            it.entries()
                .asSequence()
                .filter { !it.isDirectory }
                .map { it.name }
                .toSortedSet()
        }

        val pluginPrefix = intellijPlatform.projectName.get() + "/"

        assert(
            allFiles.all {
                it.startsWith(pluginPrefix)
            }){"files must be under plugin roots: " + allFiles.map { it.substringBefore('/') }.toSortedSet() }

        allFiles = allFiles.map { it.removePrefix(pluginPrefix) }.toSortedSet()
        assert(allFiles.isNotEmpty()) { "no libraries found in ${allFiles.joinToString { "\n  - $it" }}" }

        val kotlincFiles = allFiles.filter { it.startsWith("kotlinc/") }
        assert(kotlincFiles.contains("kotlinc/bin/kotlinc")) { "Kotlinc must be included in " + kotlincFiles.joinToString { "\n -$it" } }
        assert(kotlincFiles.contains("kotlinc/bin/kotlinc.bat")) { "Kotlinc must be included in " + kotlincFiles.joinToString { "\n -$it" } }

        allFiles = (allFiles - kotlincFiles).toSortedSet()

        // Assert expected libraries - update this list when dependencies change
        val expectedFiles = sortedSetOf(
            // LICENSE file
            "LICENSE",

            //our binaires
            "lib/ai-agents-${project.version}.jar",
            "lib/mcp-steroid-${project.version}.jar",
            "lib/ocr-common-${project.version}.jar",
            "ocr-tesseract/lib/ocr-common-${project.version}.jar",
            "ocr-tesseract/lib/ocr-tesseract-${project.version}.jar",

            //libraries
            "lib/config-1.4.3.jar",
            "lib/gson-2.10.1.jar",
            "lib/jansi-2.4.1.jar",

            "lib/ktor-events-jvm-3.1.0.jar",
            "lib/ktor-http-cio-jvm-3.1.0.jar",
            "lib/ktor-http-jvm-3.1.0.jar",
            "lib/ktor-io-jvm-3.1.0.jar",
            "lib/ktor-network-jvm-3.1.0.jar",
            "lib/ktor-serialization-jvm-3.1.0.jar",
            "lib/ktor-server-cio-jvm-3.1.0.jar",
            "lib/ktor-server-core-jvm-3.1.0.jar",
            "lib/ktor-server-sse-jvm-3.1.0.jar",
            "lib/ktor-sse-jvm-3.1.0.jar",
            "lib/ktor-utils-jvm-3.1.0.jar",
            "lib/ktor-websockets-jvm-3.1.0.jar",

            "lib/okhttp-4.11.0.jar",
            "lib/okio-jvm-3.2.0.jar",
            "lib/posthog-6.4.0.jar",
            "lib/posthog-server-2.3.0.jar",

            "ocr-tesseract/bin/ocr-tesseract",
            "ocr-tesseract/bin/ocr-tesseract.bat",
            "ocr-tesseract/lib/annotations-13.0.jar",
            "ocr-tesseract/lib/bcpkix-jdk18on-1.82.jar",
            "ocr-tesseract/lib/bcprov-jdk18on-1.82.jar",
            "ocr-tesseract/lib/bcutil-jdk18on-1.82.jar",
            "ocr-tesseract/lib/commons-io-2.21.0.jar",
            "ocr-tesseract/lib/commons-logging-1.3.5.jar",
            "ocr-tesseract/lib/fontbox-3.0.6.jar",
            "ocr-tesseract/lib/jai-imageio-core-1.4.0.jar",
            "ocr-tesseract/lib/javacpp-1.5.12-android-arm64.jar",
            "ocr-tesseract/lib/javacpp-1.5.12-android-x86_64.jar",
            "ocr-tesseract/lib/javacpp-1.5.12-ios-arm64.jar",
            "ocr-tesseract/lib/javacpp-1.5.12-ios-x86_64.jar",
            "ocr-tesseract/lib/javacpp-1.5.12-linux-arm64.jar",
            "ocr-tesseract/lib/javacpp-1.5.12-linux-ppc64le.jar",
            "ocr-tesseract/lib/javacpp-1.5.12-linux-riscv64.jar",
            "ocr-tesseract/lib/javacpp-1.5.12-linux-x86_64.jar",
            "ocr-tesseract/lib/javacpp-1.5.12-macosx-arm64.jar",
            "ocr-tesseract/lib/javacpp-1.5.12-macosx-x86_64.jar",
            "ocr-tesseract/lib/javacpp-1.5.12-windows-x86_64.jar",
            "ocr-tesseract/lib/javacpp-1.5.12.jar",
            "ocr-tesseract/lib/javacpp-platform-1.5.12.jar",
            "ocr-tesseract/lib/jbig2-imageio-3.0.4.jar",
            "ocr-tesseract/lib/jboss-logging-3.1.4.GA.jar",
            "ocr-tesseract/lib/jboss-vfs-3.2.17.Final.jar",
            "ocr-tesseract/lib/jna-5.18.1.jar",
            "ocr-tesseract/lib/kotlin-stdlib-2.2.21.jar",
            "ocr-tesseract/lib/kotlinx-serialization-core-jvm-1.7.3.jar",
            "ocr-tesseract/lib/kotlinx-serialization-json-jvm-1.7.3.jar",
            "ocr-tesseract/lib/lept4j-1.22.0.jar",
            "ocr-tesseract/lib/leptonica-1.85.0-1.5.12-android-arm64.jar",
            "ocr-tesseract/lib/leptonica-1.85.0-1.5.12-android-x86_64.jar",
            "ocr-tesseract/lib/leptonica-1.85.0-1.5.12-linux-arm64.jar",
            "ocr-tesseract/lib/leptonica-1.85.0-1.5.12-linux-x86_64.jar",
            "ocr-tesseract/lib/leptonica-1.85.0-1.5.12-macosx-arm64.jar",
            "ocr-tesseract/lib/leptonica-1.85.0-1.5.12-macosx-x86_64.jar",
            "ocr-tesseract/lib/leptonica-1.85.0-1.5.12-windows-x86_64.jar",
            "ocr-tesseract/lib/leptonica-1.85.0-1.5.12.jar",
            "ocr-tesseract/lib/leptonica-platform-1.85.0-1.5.12.jar",
            "ocr-tesseract/lib/pdfbox-3.0.6.jar",
            "ocr-tesseract/lib/pdfbox-debugger-3.0.6.jar",
            "ocr-tesseract/lib/pdfbox-io-3.0.6.jar",
            "ocr-tesseract/lib/pdfbox-tools-3.0.6.jar",
            "ocr-tesseract/lib/picocli-4.7.7.jar",
            "ocr-tesseract/lib/slf4j-api-2.0.17.jar",
            "ocr-tesseract/lib/tess4j-5.17.0.jar",
            "ocr-tesseract/lib/tesseract-5.5.1-1.5.12-android-arm64.jar",
            "ocr-tesseract/lib/tesseract-5.5.1-1.5.12-android-x86_64.jar",
            "ocr-tesseract/lib/tesseract-5.5.1-1.5.12-linux-arm64.jar",
            "ocr-tesseract/lib/tesseract-5.5.1-1.5.12-linux-x86_64.jar",
            "ocr-tesseract/lib/tesseract-5.5.1-1.5.12-macosx-arm64.jar",
            "ocr-tesseract/lib/tesseract-5.5.1-1.5.12-macosx-x86_64.jar",
            "ocr-tesseract/lib/tesseract-5.5.1-1.5.12-windows-x86_64.jar",
            "ocr-tesseract/lib/tesseract-5.5.1-1.5.12.jar",
            "ocr-tesseract/lib/tesseract-platform-5.5.1-1.5.12.jar",
            "ocr-tesseract/tessdata/eng.traineddata",
            "ocr-tesseract/tessdata/osd.traineddata",
        ).toSortedSet()

        if (allFiles != expectedFiles) {
            val missing = expectedFiles - allFiles
            val unexpected = allFiles - expectedFiles
            throw GradleException(buildString {
                appendLine("Bundled libraries mismatch!")
                if (missing.isNotEmpty()) {
                    appendLine("Missing libraries:")
                    missing.forEach { appendLine("  - $it") }
                }
                if (unexpected.isNotEmpty()) {
                    appendLine("Unexpected libraries:")
                    unexpected.forEach { appendLine("  - $it") }
                }
                appendLine()
                appendLine("Actual libraries: ")
                allFiles.forEach { appendLine("  - $it") }
                appendLine()
                appendLine("Update expectedLibraries in build.gradle.kts if this change is intentional.")
            })
        }
    }
}

tasks.verifyPlugin {
    dependsOn(verifyBundledLibraries)
}

// Deploy plugin to running IDEs with hot-reload support
val deployPlugin by tasks.registering {
    group = "intellij platform"
    description = "Deploy plugin to running IDEs"
    dependsOn(verifyBundledLibraries)
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


val deployPluginLocallyTo253 by tasks.registering(Sync::class) {
    dependsOn(tasks.buildPlugin)
    dependsOn(verifyBundledLibraries)
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
