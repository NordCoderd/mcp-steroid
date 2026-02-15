@file:Suppress("HasPlatformType")

import com.jonnyzzz.mcpSteroid.gradle.CompilePromptsTask
import com.jonnyzzz.mcpSteroid.gradle.GenerateMetadataTask
import com.jonnyzzz.mcpSteroid.gradle.HostArchitecture
import com.jonnyzzz.mcpSteroid.gradle.IdeaReleaseChannel
import com.jonnyzzz.mcpSteroid.gradle.IdeaReleaseService
import com.jonnyzzz.mcpSteroid.gradle.JetBrainsIdeProduct
import com.jonnyzzz.mcpSteroid.gradle.VerifyBundledKotlinCompatibilityTask
import com.jonnyzzz.mcpSteroid.gradle.resolveHostArchitecture
import de.undercouch.gradle.tasks.download.Download
import org.gradle.api.tasks.SourceSetContainer
import org.gradle.api.tasks.testing.Test
import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import java.net.HttpURLConnection
import java.net.URI
import java.nio.file.FileSystem
import java.security.MessageDigest
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.SortedSet

plugins {
    id("de.undercouch.download") version "5.6.0"
    id("org.jetbrains.intellij.platform") version "2.10.5"
    kotlin("jvm") version "2.3.10"
    kotlin("plugin.serialization") version "2.3.10"
}

group = "com.jonnyzzz.intellij"
val baseVersion = file("VERSION").readText().trim()
val gitHash = providers.exec {
    commandLine("git", "rev-parse", "--short", "HEAD")
}.standardOutput.asText.get().trim()
fun parseBooleanProperty(propertyName: String, raw: String): Boolean {
    return when (raw.trim().lowercase()) {
        "1", "true", "yes", "on" -> true
        "0", "false", "no", "off" -> false
        else -> error("Unsupported $propertyName value '$raw' (expected true/false or 1/0)")
    }
}

val isReleaseBuild = parseBooleanProperty(
    propertyName = "mcp.release.build",
    raw = providers.gradleProperty("mcp.release.build").orElse("false").get()
)
val snapshotTimestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
version = if (isReleaseBuild) {
    "$baseVersion-$gitHash"
} else {
    "$baseVersion-SNAPSHOT-$snapshotTimestamp-$gitHash"
}
val releaseNotesVersion = providers.gradleProperty("mcp.release.notes.version").orElse(baseVersion).get()
val releaseNotesFile = layout.projectDirectory.file("release/notes/$releaseNotesVersion.md")
val releaseNotesText = providers.provider {
    val file = releaseNotesFile.asFile
    val notes = if (file.isFile) {
        file.readText().trim()
    } else {
        "Release notes are not available for version $releaseNotesVersion."
    }

    // Keep formatting stable in plugin manager without additional markdown tooling.
    "<pre>${notes.escapeForHtmlPreBlock()}</pre>"
}

fun String.escapeForHtmlPreBlock(): String = this
    .replace("&", "&amp;")
    .replace("<", "&lt;")
    .replace(">", "&gt;")
val defaultTargetIdeProduct = "idea"
val defaultTargetIdeVersion = "2025.3"
val targetIdeProductRaw = providers.gradleProperty("mcp.platform.product").orElse(defaultTargetIdeProduct).get()
val targetIdeVersion = providers.gradleProperty("mcp.platform.version").orElse(defaultTargetIdeVersion).get()
val targetIdeProduct = when (targetIdeProductRaw.trim().lowercase()) {
    "idea", "iiu", "intellij", "intellijidea", "intellijideaultimate" -> JetBrainsIdeProduct.IntelliJIdeaUltimate
    "pycharm", "pcp", "python" -> JetBrainsIdeProduct.PyCharm
    else -> error(
        "Unsupported mcp.platform.product='$targetIdeProductRaw'. " +
                "Use one of: idea, pycharm."
    )
}
val isTargetIdeOverridden = providers.gradleProperty("mcp.platform.product").isPresent ||
        providers.gradleProperty("mcp.platform.version").isPresent
val hostArchitecture = resolveHostArchitecture()

subprojects {
    version = rootProject.version
}

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
        when (targetIdeProduct) {
            JetBrainsIdeProduct.IntelliJIdeaUltimate -> intellijIdeaUltimate(targetIdeVersion)
            JetBrainsIdeProduct.PyCharm -> pycharm(targetIdeVersion)
            JetBrainsIdeProduct.GoLand,
            JetBrainsIdeProduct.WebStorm,
            -> error("Plugin build targets IntelliJ IDEA or PyCharm only. GoLand/WebStorm are for integration tests.")
        }
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

tasks.withType<KotlinCompile>().configureEach {
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
        changeNotes = releaseNotesText

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

val verifyIntellijMajorReleaseAlignment by tasks.registering {
    group = "verification"
    description = "Assert configured IntelliJ major matches latest stable IntelliJ major from products service"
    inputs.property("targetIdeProduct", targetIdeProduct.name)
    inputs.property("targetIdeVersion", targetIdeVersion)
    inputs.property("isTargetIdeOverridden", isTargetIdeOverridden)

    doLast {
        if (isTargetIdeOverridden) {
            logger.lifecycle(
                "Skipping stable-major alignment check because mcp.platform.product/version overrides are set: {} {}.",
                targetIdeProduct.name,
                targetIdeVersion,
            )
            return@doLast
        }

        val latestStable = IdeaReleaseService.latestRelease(targetIdeProduct, IdeaReleaseChannel.STABLE)
        val configuredMajor = targetIdeVersion
            .split(".")
            .take(2)
            .joinToString(".")
        check(latestStable.majorVersion == configuredMajor) {
            "Configured ${targetIdeProduct.name} major '$configuredMajor' is stale. " +
                    "Latest stable major is '${latestStable.majorVersion}' " +
                    "(version ${latestStable.version}, build ${latestStable.build}). " +
                    "Update mcp.platform.version in build.gradle.kts."
        }
        logger.lifecycle(
            "{} major alignment verified: configured {}, latest stable {} ({} / {}).",
            targetIdeProduct.name,
            configuredMajor,
            latestStable.majorVersion,
            latestStable.version,
            latestStable.build,
        )
    }
}

val verifySupportedHostArchitecture by tasks.registering {
    group = "verification"
    description = "Assert current host machine architecture is supported by build scripts"
    inputs.property("hostArchitecture", hostArchitecture.name)
    doLast {
        check(hostArchitecture == HostArchitecture.ARM64 || hostArchitecture == HostArchitecture.X86_64) {
            "Unsupported host architecture '$hostArchitecture'"
        }
        logger.lifecycle(
            "Host architecture support verified: {} (aliases {}).",
            hostArchitecture.name,
            hostArchitecture.aliases.joinToString(", "),
        )
    }
}

tasks.named("check") {
    dependsOn(verifyIntellijMajorReleaseAlignment)
    dependsOn(verifySupportedHostArchitecture)
}

allprojects {
    tasks.withType<Test>().configureEach {
        systemProperty("mcp.steroid.test.projectHome", rootProject.layout.projectDirectory.asFile.absolutePath)
        maxHeapSize = "4g"
    }
}

val kotlincVersion = "2.3.10"
val kotlincUrl = "https://github.com/JetBrains/kotlin/releases/download/v${kotlincVersion}/kotlin-compiler-${kotlincVersion}.zip"
val kotlincSha256Url = "$kotlincUrl.sha256"
val kotlincDownloadDir = layout.buildDirectory.dir("kotlinc-zip")
val kotlincDir = layout.buildDirectory.dir("kotlinc-unpack")
val downloadConnectTimeoutMs = 30_000
val downloadReadTimeoutMs = 15 * 60_000
val downloadRetryCount = 5

fun Download.configureReliableDownload() {
    onlyIfModified(true)
    connectTimeout(downloadConnectTimeoutMs)
    readTimeout(downloadReadTimeoutMs)
    retries(downloadRetryCount)
}

val downloadKotlinc by tasks.registering {
    group = "kotlinc"
    outputs.dir(kotlincDir)

    doLast {
        val zipFileName = "kotlin-compiler-${kotlincVersion}.zip"
        val shaFileName = "$zipFileName.sha256"

        val zip = kotlincDownloadDir.get().file(zipFileName).asFile
        val shaFile = kotlincDownloadDir.get().file(shaFileName).asFile

        check(zip.isFile) { "Missing downloaded kotlinc archive: $zip" }
        check(shaFile.isFile) { "Missing downloaded kotlinc checksum: $shaFile" }

        val sha256 = shaFile
            .readText()
            .trim()
            .substringBefore(' ')

        val actualSha256 = MessageDigest.getInstance("SHA-256").run {
            update(zip.readBytes())
            digest().toHexString()
        }

        check(actualSha256 == sha256) {
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
        configureReliableDownload()
    }
    downloadKotlinc.configure { dependsOn(task) }
}

val verifyBundledKotlinCompatibility by tasks.registering(VerifyBundledKotlinCompatibilityTask::class) {
    group = "verification"
    description = "Verify bundled kotlinc is close enough to IntelliJ-bundled kotlin-stdlib"
    dependsOn(downloadKotlinc)
    dependsOn(tasks.prepareSandbox)

    val sourceSets = project.extensions.getByType<SourceSetContainer>()
    mainRuntimeClasspath.from(sourceSets.getByName("main").runtimeClasspath)
    mainRuntimeClasspath.from(configurations.getByName("intellijPlatformDependency"))
    kotlincHome.set(kotlincDir.map { it.dir("kotlinc") })
    reportFile.set(layout.buildDirectory.file("reports/kotlin-version-compatibility.txt"))
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
            filesMatching("bin/*") {
                if (!name.endsWith(".bat")) {
                    permissions { unix("rwxr-xr-x") }
                }
            }
        }
        from(downloadKotlinc) {
            into(intellijPlatform.projectName)
            filesMatching("kotlinc/bin/*") {
                if (!name.endsWith(".bat")) {
                    permissions { unix("rwxr-xr-x") }
                }
            }
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
    // Preserve executable permissions in the ZIP for scripts
    filesMatching("**/kotlinc/bin/*") {
        if (!name.endsWith(".bat")) {
            permissions { unix("rwxr-xr-x") }
        }
    }
    filesMatching("**/ocr-tesseract/bin/*") {
        if (!name.endsWith(".bat")) {
            permissions { unix("rwxr-xr-x") }
        }
    }

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

        // Read ZIP entries with executable flag detection (:X suffix)
        var allFiles: SortedSet<String> = run {
            val allFiles = mutableListOf<String>()
            zipTree(zip).visit {
                if (!isDirectory) {
                    val isExec = permissions.user.execute
                    val path = relativePath.pathString

                    allFiles += when {
                        isExec ->  "$path:X"
                        else -> path
                    }
                }
            }
            allFiles
        }.toSortedSet()

        val pluginPrefix = intellijPlatform.projectName.get() + "/"
        check(allFiles.all { it.startsWith(pluginPrefix) }) {
            "files must be under plugin roots: " + allFiles.map { it.substringBefore('/') }.toSortedSet()
        }
        allFiles = allFiles.map { it.removePrefix(pluginPrefix) }.toSortedSet()

        check(allFiles.isNotEmpty()) { "no libraries found in ${allFiles.joinToString { "\n  - $it" }}" }

        val kotlincFiles = allFiles.filter { it.startsWith("kotlinc/") }.toSortedSet()
        check(kotlincFiles.contains("kotlinc/bin/kotlinc:X")) { "Kotlinc must be included in " + kotlincFiles.joinToString { "\n $it" } }
        check(kotlincFiles.contains("kotlinc/bin/kotlinc.bat")) { "Kotlinc must be included in " + kotlincFiles.joinToString { "\n $it" } }
        allFiles = (allFiles - kotlincFiles).toCollection(sortedSetOf())


        val ocrFiles = allFiles.filter { it.startsWith("ocr-tesseract/") }.toSortedSet()
        check(ocrFiles.contains("ocr-tesseract/bin/ocr-tesseract:X")) { "ocr-tesseract must be included in " + ocrFiles.joinToString { "\n $it" } }
        check(ocrFiles.contains("ocr-tesseract/bin/ocr-tesseract.bat")) { "ocr-tesseract must be included in " + ocrFiles.joinToString { "\n $it" } }
        check(ocrFiles.contains("ocr-tesseract/tessdata/eng.traineddata")) { "ocr-tesseract must be included in " + ocrFiles.joinToString { "\n $it" } }
        check(ocrFiles.contains("ocr-tesseract/tessdata/osd.traineddata")) { "ocr-tesseract must be included in " + ocrFiles.joinToString { "\n $it" } }
        check(ocrFiles.any { it.startsWith("ocr-tesseract/lib/ocr-common-${project.version}.jar") }) { "ocr-common jar must be included in ocr-tesseract" }
        check(ocrFiles.any { it.startsWith("ocr-tesseract/lib/ocr-tesseract-${project.version}.jar") }) { "ocr-tesseract jar must be included in ocr-tesseract" }

        allFiles = (allFiles - ocrFiles).toCollection(sortedSetOf())

        // Assert expected libraries - update this list when dependencies change
        val expectedFiles = sortedSetOf(
            // LICENSE file
            "LICENSE",

            //our binaires
            "lib/ai-agents-${project.version}.jar",
            "lib/mcp-steroid-${project.version}.jar",
            "lib/ocr-common-${project.version}.jar",

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
    dependsOn(verifyBundledKotlinCompatibility)
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
