import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    kotlin("jvm")
}

kotlin {
    jvmToolchain(21)
}

repositories {
    mavenCentral()
}

val promptGeneratorClasspath by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
}

// Consume kotlinc distribution from kotlin-cli subproject
val kotlincDist by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
    attributes {
        attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage::class, "kotlinc-dist"))
    }
}

// Configuration to resolve intellij-downloader as a classpath for the download task
val ideDownloaderClasspath by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
}

dependencies {
    promptGeneratorClasspath(project(":prompt-generator"))

    testImplementation("org.junit.jupiter:junit-jupiter:5.11.0")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation(project(":test-helper"))
    testImplementation(project(":kotlin-cli"))

    kotlincDist(project(":kotlin-cli"))
    ideDownloaderClasspath(project(":intellij-downloader"))
}

val generatedSources = layout.buildDirectory.dir("generated/kotlin/prompts")
val generatedTestSources = layout.buildDirectory.dir("generated/kotlin-test/prompts")

val generatePrompts by tasks.registering(JavaExec::class) {
    classpath = promptGeneratorClasspath
    mainClass.set("com.jonnyzzz.mcpSteroid.promptgen.MainKt")
    args(
        "--input-dir", layout.projectDirectory.dir("src/main/prompts").asFile.absolutePath,
        "--output-dir", generatedSources.get().asFile.absolutePath,
        "--test-output-dir", generatedTestSources.get().asFile.absolutePath,
    )
    inputs.dir(layout.projectDirectory.dir("src/main/prompts"))
    outputs.dir(generatedSources)
    outputs.dir(generatedTestSources)
}

kotlin.sourceSets.main {
    kotlin.srcDir(generatedSources)
}

kotlin.sourceSets.test {
    kotlin.srcDir(generatedTestSources)
}

tasks.withType<KotlinCompile>().configureEach {
    dependsOn(generatePrompts)
}

// --- IDE download for KtBlock compilation tests ---

val ideDownloadDir = layout.buildDirectory.dir("ide-download")
val ideUnpackDir = layout.buildDirectory.dir("ide-unpack")

val downloadAndUnpackIde by tasks.registering(JavaExec::class) {
    group = "verification"
    description = "Download and unpack IDEA distribution for KtBlock compilation tests"
    classpath = ideDownloaderClasspath
    mainClass.set("com.jonnyzzz.mcpSteroid.ideDownloader.MainKt")
    args(
        "--product", "idea",
        "--channel", "stable",
        "--os", "linux",
        "--output-dir", ideDownloadDir.get().asFile.absolutePath,
        "--unpack-dir", ideUnpackDir.get().asFile.absolutePath,
    )
    outputs.dir(ideUnpackDir)
}

val riderDownloadDir = layout.buildDirectory.dir("rider-download")
val riderUnpackDir = layout.buildDirectory.dir("rider-unpack")

val downloadAndUnpackRider by tasks.registering(JavaExec::class) {
    group = "verification"
    description = "Download and unpack Rider distribution for KtBlock compilation tests"
    classpath = ideDownloaderClasspath
    mainClass.set("com.jonnyzzz.mcpSteroid.ideDownloader.MainKt")
    args(
        "--product", "rider",
        "--channel", "stable",
        "--os", "linux",
        "--output-dir", riderDownloadDir.get().asFile.absolutePath,
        "--unpack-dir", riderUnpackDir.get().asFile.absolutePath,
    )
    outputs.dir(riderUnpackDir)
}

tasks.test {
    useJUnitPlatform()

    dependsOn(downloadAndUnpackIde, downloadAndUnpackRider, kotlincDist)

    doFirst {
        val unpackDir = ideUnpackDir.get().asFile
        val ideHome = unpackDir.listFiles()?.firstOrNull { it.isDirectory }
        if (ideHome != null) {
            systemProperty("mcp.steroid.ide.home", ideHome.absolutePath)
        }
        // KtBlock tests will fail with a clear error if mcp.steroid.ide.home is missing;
        // non-KtBlock tests don't need it.

        val riderUnpack = riderUnpackDir.get().asFile
        val riderHome = riderUnpack.listFiles()?.firstOrNull { it.isDirectory }
            ?: error("No Rider IDE directory found in $riderUnpack. Did downloadAndUnpackRider succeed?")
        systemProperty("mcp.steroid.rider.home", riderHome.absolutePath)

        val kotlincHome = kotlincDist.singleFile
        systemProperty("mcp.steroid.kotlinc.home", kotlincHome.absolutePath)

        // ij-plugin source directory for McpScriptContext/McpScriptBuilder sources
        val ijSources = rootProject.layout.projectDirectory
            .dir("ij-plugin/src/main/kotlin").asFile.absolutePath
        systemProperty("mcp.steroid.ij.sources", ijSources)
    }
}
