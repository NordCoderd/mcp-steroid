@file:Suppress("HasPlatformType")

import com.jonnyzzz.mcpSteroid.gradle.IdeaEapArchiveResolver
import com.jonnyzzz.mcpSteroid.gradle.IdeaReleaseChannel
import com.jonnyzzz.mcpSteroid.gradle.IdeaReleaseService
import com.jonnyzzz.mcpSteroid.gradle.resolveHostArchitecture
import de.undercouch.gradle.tasks.download.Download
import org.gradle.api.tasks.testing.Test
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
    testImplementation("org.junit.jupiter:junit-jupiter-params")
    testImplementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

kotlin {
    jvmToolchain(21)
}

val ideDownloadDir = layout.buildDirectory.dir("ide-download")
// Supported host machines for archive selection: ARM64 and x86_64/amd64.
val hostArchitecture = resolveHostArchitecture()
val isArmArch = hostArchitecture.isArmArch

fun gradlePropertyOrEnv(gradlePropertyName: String, envName: String) = providers.gradleProperty(gradlePropertyName)
    .orElse(providers.environmentVariable(envName))

// IntelliJ archive selection (precedence: Gradle property > environment variable > products service default):
// Stable lane (:test-integration:test):
//   -Ptest.integration.idea.stable.url / TEST_INTEGRATION_IDEA_STABLE_URL
//   -Ptest.integration.idea.stable.version / TEST_INTEGRATION_IDEA_STABLE_VERSION
//   default source: latest stable release from https://data.services.jetbrains.com/products
// EAP lane (:test-integration:testEap):
//   -Ptest.integration.idea.eap.url / TEST_INTEGRATION_IDEA_EAP_URL
//   -Ptest.integration.idea.eap.build / TEST_INTEGRATION_IDEA_EAP_BUILD
//   default source: latest EAP release from https://data.services.jetbrains.com/products
val stableIdeaUrlOverride = gradlePropertyOrEnv(
    gradlePropertyName = "test.integration.idea.stable.url",
    envName = "TEST_INTEGRATION_IDEA_STABLE_URL",
)
val stableIdeaVersionOverride = gradlePropertyOrEnv(
    gradlePropertyName = "test.integration.idea.stable.version",
    envName = "TEST_INTEGRATION_IDEA_STABLE_VERSION",
)
val eapIdeaUrlOverride = gradlePropertyOrEnv(
    gradlePropertyName = "test.integration.idea.eap.url",
    envName = "TEST_INTEGRATION_IDEA_EAP_URL",
)
val eapIdeaBuildOverride = gradlePropertyOrEnv(
    gradlePropertyName = "test.integration.idea.eap.build",
    envName = "TEST_INTEGRATION_IDEA_EAP_BUILD",
)

val stableIdeaArchiveProvider = providers.provider {
    IdeaEapArchiveResolver.resolveStable(
        isArmArch = isArmArch,
        overrideUrl = stableIdeaUrlOverride.orNull,
        overrideVersion = stableIdeaVersionOverride.orNull,
    )
}

val ideaEapArchiveProvider = providers.provider {
    IdeaEapArchiveResolver.resolveEap(
        isArmArch = isArmArch,
        overrideUrl = eapIdeaUrlOverride.orNull,
        overrideBuild = eapIdeaBuildOverride.orNull,
    )
}

val verifyCurrentIdeaEap by tasks.registering {
    group = "verification"
    description = "Assert default testEap lane resolves to current IntelliJ IDEA EAP from products service"

    doLast {
        if (!eapIdeaUrlOverride.orNull.isNullOrBlank() || !eapIdeaBuildOverride.orNull.isNullOrBlank()) {
            logger.lifecycle("Skipping current EAP assertion because EAP override properties are set")
            return@doLast
        }

        val expected = IdeaReleaseService.latestIdeaRelease(IdeaReleaseChannel.EAP).archiveUrl(isArmArch)
        val actual = ideaEapArchiveProvider.get().url
        check(actual == expected) {
            "Expected current IDEA EAP archive '$expected' from products service, but resolved '$actual'"
        }
    }
}

val downloadIdea by tasks.registering(Download::class) {
    group = "ide"
    description = "Download IntelliJ IDEA"
    src(stableIdeaArchiveProvider.map { it.url })
    dest(ideDownloadDir.map { it.file(if (isArmArch) "idea-arm.tar.gz" else "idea-x86.tar.gz") })
    onlyIfModified(true)
}

val downloadIdeaEap by tasks.registering(Download::class) {
    group = "ide"
    description = "Download configured IntelliJ IDEA EAP"
    src(ideaEapArchiveProvider.map { it.url })
    dest(ideDownloadDir.map { it.file(if (isArmArch) "idea-eap-arm.tar.gz" else "idea-eap-x86.tar.gz") })
    onlyIfModified(true)
}

fun Test.configureIntegrationTask(ideaArchivePathProvider: () -> String) {
    useJUnitPlatform()
    testLogging {
        showStandardStreams = true
    }
    systemProperty("junit.jupiter.execution.timeout.default", "15m")

    dependsOn(pluginZip)
    doFirst {
        // Long-running integration runs can be interrupted, leaving corrupted
        // Gradle binary test result blobs that later fail with EOFException.
        delete(layout.buildDirectory.dir("test-results/${name}/binary"))

        val testOutDir = layout.buildDirectory.dir("test-logs/${name}").get().asFile
        mkdir(testOutDir)

        val resolvedPluginZip = pluginZip.singleFile
            .takeIf { it.isFile }
            ?: rootProject.layout.buildDirectory.dir("distributions").get().asFile
                .listFiles()
                ?.filter { it.isFile && it.extension == "zip" && it.name.startsWith("mcp-steroid-") }
                ?.maxByOrNull { it.lastModified() }
            ?: error(
                "Cannot resolve plugin ZIP. " +
                        "Expected pluginZip artifact or latest mcp-steroid-*.zip under ${rootProject.layout.buildDirectory.dir("distributions").get().asFile}"
            )

        systemProperty("test.integration.plugin.zip", resolvedPluginZip.absolutePath)
        systemProperty("test.integration.idea.archive", ideaArchivePathProvider())
        systemProperty("test.integration.docker", layout.projectDirectory.dir("src/test/docker").asFile.absolutePath)
        systemProperty("test.integration.testOutput", testOutDir.absolutePath)
    }
}

tasks.test {
    dependsOn(downloadIdea)
    configureIntegrationTask {
        downloadIdea.get().dest.absolutePath
    }
}

val testEap by tasks.registering(Test::class) {
    group = "verification"
    description = "Run integration tests against configured IntelliJ IDEA EAP"
    shouldRunAfter(tasks.test)
    dependsOn(verifyCurrentIdeaEap)
    dependsOn(downloadIdeaEap)
    configureIntegrationTask {
        downloadIdeaEap.get().dest.absolutePath
    }
}
