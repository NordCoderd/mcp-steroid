/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.integration.tests

import com.jonnyzzz.mcpSteroid.integration.infra.IdeTestFolders
import com.jonnyzzz.mcpSteroid.testHelper.ProjectHomeDirectory
import com.jonnyzzz.mcpSteroid.testHelper.docker.ContainerVolume
import com.jonnyzzz.mcpSteroid.testHelper.docker.ImageDriver
import com.jonnyzzz.mcpSteroid.testHelper.docker.StartContainerRequest
import com.jonnyzzz.mcpSteroid.testHelper.docker.buildDockerImage
import com.jonnyzzz.mcpSteroid.testHelper.docker.startDockerContainerAndDispose
import com.jonnyzzz.mcpSteroid.testHelper.docker.startProcessInContainer
import com.jonnyzzz.mcpSteroid.testHelper.process.assertExitCode
import com.jonnyzzz.mcpSteroid.testHelper.process.assertOutputContains
import com.jonnyzzz.mcpSteroid.testHelper.runWithCloseableStack
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Validates that the plugin compiles and packages against multiple IntelliJ Platform versions.
 *
 * Each test mounts the local project into a Docker container, cleans ignored files via
 * `git clean`, copies the clean tree to a build directory with persistent Gradle and
 * IntelliJ Platform caches, applies version patches, and runs `./gradlew :ij-plugin:buildPlugin`.
 *
 * Run:
 *   ./gradlew :test-integration:test --tests '*PluginBuildCompatibilityTest*'
 */
class PluginBuildCompatibilityTest {

    companion object {
        private lateinit var buildImage: ImageDriver

        private val projectHome: File by lazy {
            ProjectHomeDirectory.requireProjectHomeDirectory().toFile()
        }
        private val gradleHomeDir: File by lazy {
            File(System.getProperty("test.integration.build.compat.gradle.home")
                ?: error("test.integration.build.compat.gradle.home not set"))
        }
        private val ijPlatformCacheDir: File by lazy {
            File(System.getProperty("test.integration.build.compat.ij.platform")
                ?: error("test.integration.build.compat.ij.platform not set"))
        }

        @JvmStatic
        @BeforeAll
        fun setUp() {
            buildImage = buildDockerImage(
                logPrefix = "build-compat",
                dockerfilePath = File(IdeTestFolders.dockerDir, "dev-build/Dockerfile"),
                timeoutSeconds = 600,
                quietly = true,
            )
        }
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.MINUTES)
    fun `build plugin with IntelliJ 2025_3`() =
        buildPluginWithVersion("2025.3")

    @Test
    @Timeout(value = 30, unit = TimeUnit.MINUTES)
    fun `build plugin with IntelliJ 2026_1`() =
        buildPluginWithVersion("2026.1", patches = KOTLIN_2_4_PATCHES)

    // Reproduces mcp-steroid#18: 262 changed StatusBarEx.getBackgroundProcessModels()
    // return type from c.i.o.u.Pair to kotlin.Pair. Building against 262 SDK exposes the issue.
    // Requires nightly repo (internal JetBrains network — repo.labs.intellij.net).
    @Test
    @Timeout(value = 30, unit = TimeUnit.MINUTES)
    fun `build plugin with IntelliJ 262 nightly`() =
        buildPluginWithVersion("262-SNAPSHOT", patches = SNAPSHOT_262_PATCHES)

    private fun buildPluginWithVersion(
        platformVersion: String,
        patches: List<SedPatch> = emptyList(),
    ) = runWithCloseableStack { lifetime ->
        val container = startDockerContainerAndDispose(
            lifetime,
            StartContainerRequest()
                .image(buildImage)
                .logPrefix("build-${platformVersion.replace(".", "")}")
                .entryPoint("sleep", "infinity")
                .volumes(
                    ContainerVolume(projectHome, SRC_GUEST, "ro"),
                    ContainerVolume(gradleHomeDir, GRADLE_HOME_GUEST),
                    ContainerVolume(ijPlatformCacheDir, IJ_PLATFORM_GUEST),
                ),
        )

        // Copy project into container, clean ignored files, copy clean tree to build dir with caches
        container.startProcessInContainer {
            this
                .args(
                    "bash", "-c",
                    """
                    cp -a $SRC_GUEST $PREBUILD_GUEST &&
                    cd $PREBUILD_GUEST &&
                    git clean -fdx &&
                    cp -a $PREBUILD_GUEST $BUILD_GUEST &&
                    ln -sfn $IJ_PLATFORM_GUEST $BUILD_GUEST/.intellijPlatform
                    """.trimIndent().replace('\n', ' '),
                )
                .description("Prepare clean build tree for $platformVersion")
                .timeoutSeconds(120)
        }.assertExitCode(0) { "Failed to prepare build tree: $stderr" }

        // Apply sed patches to build files
        for (patch in patches) {
            container.startProcessInContainer {
                this
                    .args("sed", "-i", patch.expression, "$BUILD_GUEST/${patch.file}")
                    .description(patch.description)
                    .timeoutSeconds(5)
            }.assertExitCode(0) { "Failed to apply patch '${patch.description}': $stderr" }
        }

        // Build the plugin
        container.startProcessInContainer {
            this
                .workingDirInContainer(BUILD_GUEST)
                .args(
                    "./gradlew", ":ij-plugin:buildPlugin",
                    "-Pmcp.platform.version=$platformVersion",
                    "--no-daemon",
                    "--stacktrace",
                )
                .addEnv("GRADLE_USER_HOME", GRADLE_HOME_GUEST)
                .description("Build plugin for IntelliJ $platformVersion")
                .timeoutSeconds(1800)
        }.assertExitCode(0) { "buildPlugin failed for IntelliJ $platformVersion: $stderr" }

        // Verify plugin ZIP was produced
        container.startProcessInContainer {
            this
                .workingDirInContainer(BUILD_GUEST)
                .args("find", "ij-plugin/build/distributions", "-name", "*.zip", "-type", "f")
                .description("Verify plugin ZIP exists")
                .timeoutSeconds(10)
        }.assertExitCode(0) { "Plugin distributions directory missing: $stderr" }
            .assertOutputContains(".zip", message = "No plugin ZIP found for IntelliJ $platformVersion")
    }
}

private data class SedPatch(val file: String, val expression: String, val description: String)

private const val SRC_GUEST = "/mnt/project"
private const val PREBUILD_GUEST = "/tmp/prebuild"
private const val BUILD_GUEST = "/build"
private const val GRADLE_HOME_GUEST = "/cache/gradle-home"
private const val IJ_PLATFORM_GUEST = "/cache/ij-platform"

/** Patches to upgrade Kotlin to 2.4.0-Beta1 (needed for IntelliJ 2026.1+ which bundles Kotlin metadata 2.4.0) */
private val KOTLIN_2_4_PATCHES = listOf(
    SedPatch(
        file = "build.gradle.kts",
        expression = """s/kotlin("jvm") version "[^"]*"/kotlin("jvm") version "2.4.0-Beta1"/;""" +
            """s/kotlin("plugin.serialization") version "[^"]*"/kotlin("plugin.serialization") version "2.4.0-Beta1"/""",
        description = "Patch Kotlin version to 2.4.0-Beta1",
    ),
)

/**
 * Patches for building against 262-SNAPSHOT (nightly):
 * - Kotlin 2.4.0-Beta1 (262 bundles Kotlin metadata 2.4.0)
 * - Plugin version 2.14.0 (2.11.0 doesn't resolve nightly snapshots correctly)
 * - useInstaller = false (snapshots are Maven artifacts, not installer downloads)
 * - nightly() repo (262-SNAPSHOT is only in the nightly Maven repository)
 */
private val SNAPSHOT_262_PATCHES = KOTLIN_2_4_PATCHES + listOf(
    SedPatch(
        file = "build.gradle.kts",
        expression = """s/id("org.jetbrains.intellij.platform") version "[^"]*"/id("org.jetbrains.intellij.platform") version "2.14.0"/""",
        description = "Bump IntelliJ Platform Gradle Plugin to 2.14.0",
    ),
    SedPatch(
        file = "ij-plugin/build.gradle.kts",
        expression = """s/intellijIdeaUltimate(targetIdeVersion)/intellijIdeaUltimate(targetIdeVersion) { useInstaller = false }/""",
        description = "Disable installer mode for Maven snapshot resolution",
    ),
    SedPatch(
        file = "ij-plugin/build.gradle.kts",
        expression = """s/defaultRepositories()/defaultRepositories()\n        nightly()/""",
        description = "Add nightly repo for 262-SNAPSHOT",
    ),
)
