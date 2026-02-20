@file:Suppress("HasPlatformType")

import com.jonnyzzz.mcpSteroid.gradle.IdeaReleaseChannel
import com.jonnyzzz.mcpSteroid.gradle.IdeaReleaseService
import com.jonnyzzz.mcpSteroid.gradle.JetBrainsIdeProduct
import com.jonnyzzz.mcpSteroid.gradle.resolveHostArchitecture
import de.undercouch.gradle.tasks.download.Download
import org.gradle.api.tasks.SourceSetContainer
import org.gradle.api.tasks.testing.Test
import org.gradle.kotlin.dsl.provideDelegate
import java.net.URI

plugins {
    kotlin("jvm")
    id("de.undercouch.download")
}

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
    testImplementation(project(":agent-output-filter"))
    testImplementation(project(":ai-agents"))

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

enum class IntegrationIdeProduct(
    val id: String,
    val serviceProduct: JetBrainsIdeProduct,
    val downloadFolder: String,
    val archiveFilePrefix: String,
) {
    IDEA(
        id = "idea",
        serviceProduct = JetBrainsIdeProduct.IntelliJIdeaUltimate,
        downloadFolder = "idea",
        archiveFilePrefix = "idea",
    ),
    PYCHARM(
        id = "pycharm",
        serviceProduct = JetBrainsIdeProduct.PyCharm,
        downloadFolder = "python",
        archiveFilePrefix = "pycharm",
    ),
    GOLAND(
        id = "goland",
        serviceProduct = JetBrainsIdeProduct.GoLand,
        downloadFolder = "go",
        archiveFilePrefix = "goland",
    ),
    WEBSTORM(
        id = "webstorm",
        serviceProduct = JetBrainsIdeProduct.WebStorm,
        downloadFolder = "webstorm",
        archiveFilePrefix = "WebStorm",
    ),
}

data class ResolvedIdeArchive(
    val url: String,
    val fileName: String,
)

val ideDownloadDir = layout.buildDirectory.dir("ide-download")
// Supported host machines for archive selection: ARM64 and x86_64/amd64.
val hostArchitecture = resolveHostArchitecture()
val isArmArch = hostArchitecture.isArmArch

fun gradlePropertyOrEnv(gradlePropertyName: String, envName: String) = providers.gradleProperty(gradlePropertyName)
    .orElse(providers.environmentVariable(envName))

// IntelliJ IDEA archive selection (precedence: Gradle property > environment variable > products service default):
// Stable lane (:test-integration:test):
//   -Ptest.integration.idea.stable.url / TEST_INTEGRATION_IDEA_STABLE_URL
//   -Ptest.integration.idea.stable.version / TEST_INTEGRATION_IDEA_STABLE_VERSION
// EAP lane (:test-integration:testEap):
//   -Ptest.integration.idea.eap.url / TEST_INTEGRATION_IDEA_EAP_URL
//   -Ptest.integration.idea.eap.build / TEST_INTEGRATION_IDEA_EAP_BUILD
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

// PyCharm archive selection (precedence: Gradle property > environment variable > products service default):
// Stable lane (:test-integration:testPyCharm):
//   -Ptest.integration.pycharm.stable.url / TEST_INTEGRATION_PYCHARM_STABLE_URL
//   -Ptest.integration.pycharm.stable.version / TEST_INTEGRATION_PYCHARM_STABLE_VERSION
// EAP lane (:test-integration:testPyCharmEap):
//   -Ptest.integration.pycharm.eap.url / TEST_INTEGRATION_PYCHARM_EAP_URL
//   -Ptest.integration.pycharm.eap.build / TEST_INTEGRATION_PYCHARM_EAP_BUILD
val stablePyCharmUrlOverride = gradlePropertyOrEnv(
    gradlePropertyName = "test.integration.pycharm.stable.url",
    envName = "TEST_INTEGRATION_PYCHARM_STABLE_URL",
)
val stablePyCharmVersionOverride = gradlePropertyOrEnv(
    gradlePropertyName = "test.integration.pycharm.stable.version",
    envName = "TEST_INTEGRATION_PYCHARM_STABLE_VERSION",
)
val eapPyCharmUrlOverride = gradlePropertyOrEnv(
    gradlePropertyName = "test.integration.pycharm.eap.url",
    envName = "TEST_INTEGRATION_PYCHARM_EAP_URL",
)
val eapPyCharmBuildOverride = gradlePropertyOrEnv(
    gradlePropertyName = "test.integration.pycharm.eap.build",
    envName = "TEST_INTEGRATION_PYCHARM_EAP_BUILD",
)

// GoLand archive selection (precedence: Gradle property > environment variable > products service default):
// Stable lane (:test-integration:testGoLand):
//   -Ptest.integration.goland.stable.url / TEST_INTEGRATION_GOLAND_STABLE_URL
//   -Ptest.integration.goland.stable.version / TEST_INTEGRATION_GOLAND_STABLE_VERSION
// EAP lane (:test-integration:testGoLandEap):
//   -Ptest.integration.goland.eap.url / TEST_INTEGRATION_GOLAND_EAP_URL
//   -Ptest.integration.goland.eap.build / TEST_INTEGRATION_GOLAND_EAP_BUILD
val stableGoLandUrlOverride = gradlePropertyOrEnv(
    gradlePropertyName = "test.integration.goland.stable.url",
    envName = "TEST_INTEGRATION_GOLAND_STABLE_URL",
)
val stableGoLandVersionOverride = gradlePropertyOrEnv(
    gradlePropertyName = "test.integration.goland.stable.version",
    envName = "TEST_INTEGRATION_GOLAND_STABLE_VERSION",
)
val eapGoLandUrlOverride = gradlePropertyOrEnv(
    gradlePropertyName = "test.integration.goland.eap.url",
    envName = "TEST_INTEGRATION_GOLAND_EAP_URL",
)
val eapGoLandBuildOverride = gradlePropertyOrEnv(
    gradlePropertyName = "test.integration.goland.eap.build",
    envName = "TEST_INTEGRATION_GOLAND_EAP_BUILD",
)

// WebStorm archive selection (precedence: Gradle property > environment variable > products service default):
// Stable lane (:test-integration:testWebStorm):
//   -Ptest.integration.webstorm.stable.url / TEST_INTEGRATION_WEBSTORM_STABLE_URL
//   -Ptest.integration.webstorm.stable.version / TEST_INTEGRATION_WEBSTORM_STABLE_VERSION
// EAP lane (:test-integration:testWebStormEap):
//   -Ptest.integration.webstorm.eap.url / TEST_INTEGRATION_WEBSTORM_EAP_URL
//   -Ptest.integration.webstorm.eap.build / TEST_INTEGRATION_WEBSTORM_EAP_BUILD
val stableWebStormUrlOverride = gradlePropertyOrEnv(
    gradlePropertyName = "test.integration.webstorm.stable.url",
    envName = "TEST_INTEGRATION_WEBSTORM_STABLE_URL",
)
val stableWebStormVersionOverride = gradlePropertyOrEnv(
    gradlePropertyName = "test.integration.webstorm.stable.version",
    envName = "TEST_INTEGRATION_WEBSTORM_STABLE_VERSION",
)
val eapWebStormUrlOverride = gradlePropertyOrEnv(
    gradlePropertyName = "test.integration.webstorm.eap.url",
    envName = "TEST_INTEGRATION_WEBSTORM_EAP_URL",
)
val eapWebStormBuildOverride = gradlePropertyOrEnv(
    gradlePropertyName = "test.integration.webstorm.eap.build",
    envName = "TEST_INTEGRATION_WEBSTORM_EAP_BUILD",
)

fun archiveUrlForVersion(
    product: IntegrationIdeProduct,
    version: String,
    isArmArch: Boolean,
): String {
    val normalizedVersion = version.trim()
    require(normalizedVersion.isNotEmpty()) { "${product.id} stable version must not be blank" }
    val suffix = if (isArmArch) "-aarch64.tar.gz" else ".tar.gz"
    return "https://download.jetbrains.com/${product.downloadFolder}/${product.archiveFilePrefix}-$normalizedVersion$suffix"
}

fun archiveUrlForBuild(
    product: IntegrationIdeProduct,
    build: String,
    isArmArch: Boolean,
): String {
    val normalizedBuild = build.trim()
    require(normalizedBuild.isNotEmpty()) { "${product.id} EAP build must not be blank" }
    val suffix = if (isArmArch) "-aarch64.tar.gz" else ".tar.gz"
    return "https://download.jetbrains.com/${product.downloadFolder}/${product.archiveFilePrefix}-$normalizedBuild$suffix"
}

fun stableArchive(
    product: IntegrationIdeProduct,
    overrideUrl: String?,
    overrideVersion: String?,
): ResolvedIdeArchive {
    val fallbackFileName = if (isArmArch) "${product.id}-stable-arm.tar.gz" else "${product.id}-stable-x86.tar.gz"
    val resolvedUrl = overrideUrl?.trim()?.takeIf { it.isNotEmpty() }
        ?: overrideVersion?.trim()?.takeIf { it.isNotEmpty() }?.let { archiveUrlForVersion(product, it, isArmArch) }
        ?: IdeaReleaseService.latestRelease(product.serviceProduct, IdeaReleaseChannel.STABLE).archiveUrl(isArmArch)

    val fileName = archiveFileNameFromUrl(resolvedUrl, fallbackFileName)
    return ResolvedIdeArchive(url = resolvedUrl, fileName = fileName)
}

fun eapArchive(
    product: IntegrationIdeProduct,
    overrideUrl: String?,
    overrideBuild: String?,
): ResolvedIdeArchive {
    val fallbackFileName = if (isArmArch) "${product.id}-eap-arm.tar.gz" else "${product.id}-eap-x86.tar.gz"
    val resolvedUrl = overrideUrl?.trim()?.takeIf { it.isNotEmpty() }
        ?: overrideBuild?.trim()?.takeIf { it.isNotEmpty() }?.let { archiveUrlForBuild(product, it, isArmArch) }
        ?: IdeaReleaseService.latestRelease(product.serviceProduct, IdeaReleaseChannel.EAP).archiveUrl(isArmArch)

    val fileName = archiveFileNameFromUrl(resolvedUrl, fallbackFileName)
    return ResolvedIdeArchive(url = resolvedUrl, fileName = fileName)
}

fun archiveFileNameFromUrl(url: String, fallbackFileName: String): String {
    val fileName = runCatching { URI(url).path.substringAfterLast('/') }.getOrNull()
    return fileName?.takeIf { it.isNotBlank() } ?: fallbackFileName
}

val stableIdeaArchiveProvider = providers.provider {
    stableArchive(
        product = IntegrationIdeProduct.IDEA,
        overrideUrl = stableIdeaUrlOverride.orNull,
        overrideVersion = stableIdeaVersionOverride.orNull,
    )
}

val ideaEapArchiveProvider = providers.provider {
    eapArchive(
        product = IntegrationIdeProduct.IDEA,
        overrideUrl = eapIdeaUrlOverride.orNull,
        overrideBuild = eapIdeaBuildOverride.orNull,
    )
}

val stablePyCharmArchiveProvider = providers.provider {
    stableArchive(
        product = IntegrationIdeProduct.PYCHARM,
        overrideUrl = stablePyCharmUrlOverride.orNull,
        overrideVersion = stablePyCharmVersionOverride.orNull,
    )
}

val pyCharmEapArchiveProvider = providers.provider {
    eapArchive(
        product = IntegrationIdeProduct.PYCHARM,
        overrideUrl = eapPyCharmUrlOverride.orNull,
        overrideBuild = eapPyCharmBuildOverride.orNull,
    )
}

val stableGoLandArchiveProvider = providers.provider {
    stableArchive(
        product = IntegrationIdeProduct.GOLAND,
        overrideUrl = stableGoLandUrlOverride.orNull,
        overrideVersion = stableGoLandVersionOverride.orNull,
    )
}

val goLandEapArchiveProvider = providers.provider {
    eapArchive(
        product = IntegrationIdeProduct.GOLAND,
        overrideUrl = eapGoLandUrlOverride.orNull,
        overrideBuild = eapGoLandBuildOverride.orNull,
    )
}

val stableWebStormArchiveProvider = providers.provider {
    stableArchive(
        product = IntegrationIdeProduct.WEBSTORM,
        overrideUrl = stableWebStormUrlOverride.orNull,
        overrideVersion = stableWebStormVersionOverride.orNull,
    )
}

val webStormEapArchiveProvider = providers.provider {
    eapArchive(
        product = IntegrationIdeProduct.WEBSTORM,
        overrideUrl = eapWebStormUrlOverride.orNull,
        overrideBuild = eapWebStormBuildOverride.orNull,
    )
}

val verifyCurrentIdeaEap by tasks.registering {
    group = "verification"
    description = "Assert default IDEA EAP lane resolves to current IntelliJ IDEA EAP from products service"

    doLast {
        if (!eapIdeaUrlOverride.orNull.isNullOrBlank() || !eapIdeaBuildOverride.orNull.isNullOrBlank()) {
            logger.lifecycle("Skipping current IDEA EAP assertion because EAP override properties are set")
            return@doLast
        }

        val expected = IdeaReleaseService.latestRelease(
            product = JetBrainsIdeProduct.IntelliJIdeaUltimate,
            channel = IdeaReleaseChannel.EAP,
        ).archiveUrl(isArmArch)
        val actual = ideaEapArchiveProvider.get().url
        check(actual == expected) {
            "Expected current IDEA EAP archive '$expected' from products service, but resolved '$actual'"
        }
    }
}

val verifyCurrentPyCharmEap by tasks.registering {
    group = "verification"
    description = "Assert default PyCharm EAP lane resolves to current PyCharm EAP from products service"

    doLast {
        if (!eapPyCharmUrlOverride.orNull.isNullOrBlank() || !eapPyCharmBuildOverride.orNull.isNullOrBlank()) {
            logger.lifecycle("Skipping current PyCharm EAP assertion because EAP override properties are set")
            return@doLast
        }

        val expected = IdeaReleaseService.latestRelease(
            product = JetBrainsIdeProduct.PyCharm,
            channel = IdeaReleaseChannel.EAP,
        ).archiveUrl(isArmArch)
        val actual = pyCharmEapArchiveProvider.get().url
        check(actual == expected) {
            "Expected current PyCharm EAP archive '$expected' from products service, but resolved '$actual'"
        }
    }
}

val downloadIdea by tasks.registering(Download::class) {
    group = "ide"
    description = "Download configured IntelliJ IDEA"
    src(stableIdeaArchiveProvider.map { it.url })
    dest(ideDownloadDir.map { it.file(stableIdeaArchiveProvider.get().fileName) })
    onlyIfModified(true)
    connectTimeout(30_000)
    readTimeout(15 * 60_000)
    retries(5)
}

val downloadIdeaEap by tasks.registering(Download::class) {
    group = "ide"
    description = "Download configured IntelliJ IDEA EAP"
    src(ideaEapArchiveProvider.map { it.url })
    dest(ideDownloadDir.map { it.file(ideaEapArchiveProvider.get().fileName) })
    onlyIfModified(true)
    connectTimeout(30_000)
    readTimeout(15 * 60_000)
    retries(5)
}

val downloadPyCharm by tasks.registering(Download::class) {
    group = "ide"
    description = "Download configured PyCharm"
    src(stablePyCharmArchiveProvider.map { it.url })
    dest(ideDownloadDir.map { it.file(stablePyCharmArchiveProvider.get().fileName) })
    onlyIfModified(true)
    connectTimeout(30_000)
    readTimeout(15 * 60_000)
    retries(5)
}

val downloadPyCharmEap by tasks.registering(Download::class) {
    group = "ide"
    description = "Download configured PyCharm EAP"
    src(pyCharmEapArchiveProvider.map { it.url })
    dest(ideDownloadDir.map { it.file(pyCharmEapArchiveProvider.get().fileName) })
    onlyIfModified(true)
    connectTimeout(30_000)
    readTimeout(15 * 60_000)
    retries(5)
}

val verifyCurrentGoLandEap by tasks.registering {
    group = "verification"
    description = "Assert default GoLand EAP lane resolves to current GoLand EAP from products service"

    doLast {
        if (!eapGoLandUrlOverride.orNull.isNullOrBlank() || !eapGoLandBuildOverride.orNull.isNullOrBlank()) {
            logger.lifecycle("Skipping current GoLand EAP assertion because EAP override properties are set")
            return@doLast
        }

        val expected = IdeaReleaseService.latestRelease(
            product = JetBrainsIdeProduct.GoLand,
            channel = IdeaReleaseChannel.EAP,
        ).archiveUrl(isArmArch)
        val actual = goLandEapArchiveProvider.get().url
        check(actual == expected) {
            "Expected current GoLand EAP archive '$expected' from products service, but resolved '$actual'"
        }
    }
}

val verifyCurrentWebStormEap by tasks.registering {
    group = "verification"
    description = "Assert default WebStorm EAP lane resolves to current WebStorm EAP from products service"

    doLast {
        if (!eapWebStormUrlOverride.orNull.isNullOrBlank() || !eapWebStormBuildOverride.orNull.isNullOrBlank()) {
            logger.lifecycle("Skipping current WebStorm EAP assertion because EAP override properties are set")
            return@doLast
        }

        val expected = IdeaReleaseService.latestRelease(
            product = JetBrainsIdeProduct.WebStorm,
            channel = IdeaReleaseChannel.EAP,
        ).archiveUrl(isArmArch)
        val actual = webStormEapArchiveProvider.get().url
        check(actual == expected) {
            "Expected current WebStorm EAP archive '$expected' from products service, but resolved '$actual'"
        }
    }
}

val downloadGoLand by tasks.registering(Download::class) {
    group = "ide"
    description = "Download configured GoLand"
    src(stableGoLandArchiveProvider.map { it.url })
    dest(ideDownloadDir.map { it.file(stableGoLandArchiveProvider.get().fileName) })
    onlyIfModified(true)
    connectTimeout(30_000)
    readTimeout(15 * 60_000)
    retries(5)
}

val downloadGoLandEap by tasks.registering(Download::class) {
    group = "ide"
    description = "Download configured GoLand EAP"
    src(goLandEapArchiveProvider.map { it.url })
    dest(ideDownloadDir.map { it.file(goLandEapArchiveProvider.get().fileName) })
    onlyIfModified(true)
    connectTimeout(30_000)
    readTimeout(15 * 60_000)
    retries(5)
}

val downloadWebStorm by tasks.registering(Download::class) {
    group = "ide"
    description = "Download configured WebStorm"
    src(stableWebStormArchiveProvider.map { it.url })
    dest(ideDownloadDir.map { it.file(stableWebStormArchiveProvider.get().fileName) })
    onlyIfModified(true)
    connectTimeout(30_000)
    readTimeout(15 * 60_000)
    retries(5)
}

val downloadWebStormEap by tasks.registering(Download::class) {
    group = "ide"
    description = "Download configured WebStorm EAP"
    src(webStormEapArchiveProvider.map { it.url })
    dest(ideDownloadDir.map { it.file(webStormEapArchiveProvider.get().fileName) })
    onlyIfModified(true)
    connectTimeout(30_000)
    readTimeout(15 * 60_000)
    retries(5)
}

val integrationTestSourceSet = extensions.getByType<SourceSetContainer>().named("test")

fun Test.configureIntegrationTask(
    ideaArchivePathProvider: () -> String,
    ideProduct: IntegrationIdeProduct,
    includeClassNamePatterns: List<String> = emptyList(),
) {
    useJUnitPlatform()
    testClassesDirs = integrationTestSourceSet.get().output.classesDirs
    classpath = integrationTestSourceSet.get().runtimeClasspath
    testLogging {
        showStandardStreams = true
    }
    systemProperty("junit.jupiter.execution.timeout.default", "15m")

    if (includeClassNamePatterns.isNotEmpty()) {
        filter {
            includeClassNamePatterns.forEach { includeTestsMatching(it) }
        }
    }

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

        val archivePath = ideaArchivePathProvider()
        systemProperty("test.integration.plugin.zip", resolvedPluginZip.absolutePath)
        // Backward compatible old property name:
        systemProperty("test.integration.idea.archive", archivePath)
        // Generic property name:
        systemProperty("test.integration.ide.archive", archivePath)
        systemProperty("test.integration.ide.product", ideProduct.id)
        systemProperty("test.integration.docker", layout.projectDirectory.dir("src/test/docker").asFile.absolutePath)
        systemProperty("test.integration.testOutput", testOutDir.absolutePath)
    }
}

val ideaReleaseSmokeTests = listOf(
    "com.jonnyzzz.mcpSteroid.integration.tests.DialogKiller*",
    "com.jonnyzzz.mcpSteroid.integration.tests.EapSmokeTest*",
    "com.jonnyzzz.mcpSteroid.integration.tests.IntelliJContainerTest*",
    "com.jonnyzzz.mcpSteroid.integration.tests.InfrastructureTest*",
    "com.jonnyzzz.mcpSteroid.integration.tests.NpxToolVisibilityTest*",
    "com.jonnyzzz.mcpSteroid.integration.tests.WhatYouSeeTest*",
)

val pyCharmReleaseSmokeTests = listOf(
    "com.jonnyzzz.mcpSteroid.integration.tests.EapSmokeTest*",
    "com.jonnyzzz.mcpSteroid.integration.tests.PyCharmContainerIntegrationTest",
    "com.jonnyzzz.mcpSteroid.integration.tests.PyCharmMcpExecutionIntegrationTest",
)

val goLandReleaseSmokeTests = listOf(
    "com.jonnyzzz.mcpSteroid.integration.tests.EapSmokeTest*",
)

val webStormReleaseSmokeTests = listOf(
    "com.jonnyzzz.mcpSteroid.integration.tests.EapSmokeTest*",
)

tasks.test {
    // Integration tests require downloading IDE archives and Docker — run explicitly via named tasks below
    enabled = false
}

val testIdea by tasks.registering(Test::class) {
    group = "verification"
    description = "Run integration tests against configured IntelliJ IDEA stable"
    dependsOn(downloadIdea)
    configureIntegrationTask(
        ideProduct = IntegrationIdeProduct.IDEA,
        ideaArchivePathProvider = { downloadIdea.get().dest.absolutePath },
    )
}

val testEap by tasks.registering(Test::class) {
    group = "verification"
    description = "Run integration tests against configured IntelliJ IDEA EAP"
    shouldRunAfter(testIdea)
    dependsOn(verifyCurrentIdeaEap)
    dependsOn(downloadIdeaEap)
    configureIntegrationTask(
        ideProduct = IntegrationIdeProduct.IDEA,
        ideaArchivePathProvider = { downloadIdeaEap.get().dest.absolutePath },
    )
}

val testPyCharm by tasks.registering(Test::class) {
    group = "verification"
    description = "Run integration tests against configured PyCharm stable"
    shouldRunAfter(testIdea)
    dependsOn(downloadPyCharm)
    configureIntegrationTask(
        ideProduct = IntegrationIdeProduct.PYCHARM,
        ideaArchivePathProvider = { downloadPyCharm.get().dest.absolutePath },
    )
}

val testPyCharmEap by tasks.registering(Test::class) {
    group = "verification"
    description = "Run integration tests against configured PyCharm EAP"
    shouldRunAfter(testPyCharm)
    dependsOn(verifyCurrentPyCharmEap)
    dependsOn(downloadPyCharmEap)
    configureIntegrationTask(
        ideProduct = IntegrationIdeProduct.PYCHARM,
        ideaArchivePathProvider = { downloadPyCharmEap.get().dest.absolutePath },
    )
}

val testGoLand by tasks.registering(Test::class) {
    group = "verification"
    description = "Run integration tests against configured GoLand stable"
    shouldRunAfter(testIdea)
    dependsOn(downloadGoLand)
    configureIntegrationTask(
        ideProduct = IntegrationIdeProduct.GOLAND,
        ideaArchivePathProvider = { downloadGoLand.get().dest.absolutePath },
    )
}

val testGoLandEap by tasks.registering(Test::class) {
    group = "verification"
    description = "Run integration tests against configured GoLand EAP"
    shouldRunAfter(testGoLand)
    dependsOn(verifyCurrentGoLandEap)
    dependsOn(downloadGoLandEap)
    configureIntegrationTask(
        ideProduct = IntegrationIdeProduct.GOLAND,
        ideaArchivePathProvider = { downloadGoLandEap.get().dest.absolutePath },
    )
}

val testWebStorm by tasks.registering(Test::class) {
    group = "verification"
    description = "Run integration tests against configured WebStorm stable"
    shouldRunAfter(testIdea)
    dependsOn(downloadWebStorm)
    configureIntegrationTask(
        ideProduct = IntegrationIdeProduct.WEBSTORM,
        ideaArchivePathProvider = { downloadWebStorm.get().dest.absolutePath },
    )
}

val testWebStormEap by tasks.registering(Test::class) {
    group = "verification"
    description = "Run integration tests against configured WebStorm EAP"
    shouldRunAfter(testWebStorm)
    dependsOn(verifyCurrentWebStormEap)
    dependsOn(downloadWebStormEap)
    configureIntegrationTask(
        ideProduct = IntegrationIdeProduct.WEBSTORM,
        ideaArchivePathProvider = { downloadWebStormEap.get().dest.absolutePath },
    )
}

val testReleaseSmokeIdea by tasks.registering(Test::class) {
    group = "verification"
    description = "Run selected release-smoke integration tests on IntelliJ IDEA stable"
    shouldRunAfter(testIdea)
    dependsOn(downloadIdea)
    configureIntegrationTask(
        ideProduct = IntegrationIdeProduct.IDEA,
        ideaArchivePathProvider = { downloadIdea.get().dest.absolutePath },
        includeClassNamePatterns = ideaReleaseSmokeTests,
    )
}

val testReleaseSmokeIdeaEap by tasks.registering(Test::class) {
    group = "verification"
    description = "Run selected release-smoke integration tests on IntelliJ IDEA EAP"
    shouldRunAfter(testReleaseSmokeIdea)
    dependsOn(verifyCurrentIdeaEap)
    dependsOn(downloadIdeaEap)
    configureIntegrationTask(
        ideProduct = IntegrationIdeProduct.IDEA,
        ideaArchivePathProvider = { downloadIdeaEap.get().dest.absolutePath },
        includeClassNamePatterns = ideaReleaseSmokeTests,
    )
}

val testReleaseSmokePyCharm by tasks.registering(Test::class) {
    group = "verification"
    description = "Run dedicated PyCharm release-smoke integration tests on PyCharm stable"
    shouldRunAfter(testReleaseSmokeIdeaEap)
    dependsOn(downloadPyCharm)
    configureIntegrationTask(
        ideProduct = IntegrationIdeProduct.PYCHARM,
        ideaArchivePathProvider = { downloadPyCharm.get().dest.absolutePath },
        includeClassNamePatterns = pyCharmReleaseSmokeTests,
    )
}

val testReleaseSmokePyCharmEap by tasks.registering(Test::class) {
    group = "verification"
    description = "Run dedicated PyCharm release-smoke integration tests on PyCharm EAP"
    shouldRunAfter(testReleaseSmokePyCharm)
    dependsOn(verifyCurrentPyCharmEap)
    dependsOn(downloadPyCharmEap)
    configureIntegrationTask(
        ideProduct = IntegrationIdeProduct.PYCHARM,
        ideaArchivePathProvider = { downloadPyCharmEap.get().dest.absolutePath },
        includeClassNamePatterns = pyCharmReleaseSmokeTests,
    )
}

val testReleaseSmokeGoLand by tasks.registering(Test::class) {
    group = "verification"
    description = "Run release-smoke integration tests on GoLand stable"
    shouldRunAfter(testReleaseSmokePyCharmEap)
    dependsOn(downloadGoLand)
    configureIntegrationTask(
        ideProduct = IntegrationIdeProduct.GOLAND,
        ideaArchivePathProvider = { downloadGoLand.get().dest.absolutePath },
        includeClassNamePatterns = goLandReleaseSmokeTests,
    )
}

val testReleaseSmokeGoLandEap by tasks.registering(Test::class) {
    group = "verification"
    description = "Run release-smoke integration tests on GoLand EAP"
    shouldRunAfter(testReleaseSmokeGoLand)
    dependsOn(verifyCurrentGoLandEap)
    dependsOn(downloadGoLandEap)
    configureIntegrationTask(
        ideProduct = IntegrationIdeProduct.GOLAND,
        ideaArchivePathProvider = { downloadGoLandEap.get().dest.absolutePath },
        includeClassNamePatterns = goLandReleaseSmokeTests,
    )
}

val testReleaseSmokeWebStorm by tasks.registering(Test::class) {
    group = "verification"
    description = "Run release-smoke integration tests on WebStorm stable"
    shouldRunAfter(testReleaseSmokeGoLandEap)
    dependsOn(downloadWebStorm)
    configureIntegrationTask(
        ideProduct = IntegrationIdeProduct.WEBSTORM,
        ideaArchivePathProvider = { downloadWebStorm.get().dest.absolutePath },
        includeClassNamePatterns = webStormReleaseSmokeTests,
    )
}

val testReleaseSmokeWebStormEap by tasks.registering(Test::class) {
    group = "verification"
    description = "Run release-smoke integration tests on WebStorm EAP"
    shouldRunAfter(testReleaseSmokeWebStorm)
    dependsOn(verifyCurrentWebStormEap)
    dependsOn(downloadWebStormEap)
    configureIntegrationTask(
        ideProduct = IntegrationIdeProduct.WEBSTORM,
        ideaArchivePathProvider = { downloadWebStormEap.get().dest.absolutePath },
        includeClassNamePatterns = webStormReleaseSmokeTests,
    )
}

tasks.register("testReleaseSmokeMatrix") {
    group = "verification"
    description = "Run release-smoke matrix: [IDEA, PyCharm, GoLand, WebStorm] x [stable, EAP]"
    dependsOn(
        testReleaseSmokeIdea,
        testReleaseSmokeIdeaEap,
        testReleaseSmokePyCharm,
        testReleaseSmokePyCharmEap,
        testReleaseSmokeGoLand,
        testReleaseSmokeGoLandEap,
        testReleaseSmokeWebStorm,
        testReleaseSmokeWebStormEap,
    )
}

tasks.register("testReleaseCategory") {
    group = "verification"
    description = "Run selected release category integration tests: [IDEA, PyCharm, GoLand, WebStorm] x [stable, EAP]"
    dependsOn("testReleaseSmokeMatrix")
}
