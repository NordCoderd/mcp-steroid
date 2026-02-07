/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.integration

import com.jonnyzzz.mcpSteroid.testHelper.docker.DockerDriver
import com.jonnyzzz.mcpSteroid.testHelper.ProcessResult
import java.io.File
import java.nio.file.Files
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Manages a Docker container running IntelliJ IDEA with MCP Steroid plugin.
 * Assembles the Docker build context from separate artifacts and starts a named container.
 *
 * The container is NOT removed after the test — it stays around for debugging.
 * It IS removed before the next test run (by name).
 *
 * All IDE directories, video, and screenshots are mounted to a timestamped
 * run directory under testOutputDir for easy inspection and debugging.
 */
class IdeContainerSession(
    private val scope: DockerDriver,
    private val containerId: String,
    val containerName: String,
    /** Host directory for this test run (timestamped) */
    val runDir: File,
) {
    val videoDir: File get() = File(runDir, "video")
    val videoFile: File get() = File(videoDir, "recording.mp4")
    val screenshotDir: File get() = File(runDir, "screenshots")
    val configDir: File get() = File(runDir, "ide-config")
    val systemDir: File get() = File(runDir, "ide-system")
    val logDir: File get() = File(runDir, "ide-log")
    val pluginsDir: File get() = File(runDir, "ide-plugins")

    /**
     * Wait for the IDE to be ready (MCP server responding).
     * Polls /tmp/ide-ready marker inside the container.
     */
    fun waitForIdeReady(timeoutSeconds: Long = 300) {
        val startTime = System.currentTimeMillis()
        val deadline = startTime + timeoutSeconds * 1000

        while (System.currentTimeMillis() < deadline) {
            val result = scope.runInContainer(
                containerId,
                listOf("test", "-f", "/tmp/ide-ready"),
                timeoutSeconds = 5,
            )
            if (result.exitCode == 0) {
                println("[IDE-AGENT] IDE is ready (took ${(System.currentTimeMillis() - startTime) / 1000}s)")
                return
            }
            Thread.sleep(3000)
        }
        error("IDE did not become ready within ${timeoutSeconds}s")
    }

    /**
     * Take a screenshot of the IDE display via scrot.
     */
    fun takeScreenshot(localPath: File) {
        val remotePath = "/tmp/screenshot.png"
        scope.runInContainer(containerId, listOf("scrot", remotePath), timeoutSeconds = 10)
        scope.copyFromContainer(containerId, remotePath, localPath)
    }

    /**
     * Stop video recording inside the container (sends SIGINT to ffmpeg).
     * The video file is already on the host via the mounted volume.
     */
    fun stopVideoRecording() {
        scope.runInContainer(containerId, listOf("pkill", "-SIGINT", "ffmpeg"), timeoutSeconds = 5)
        Thread.sleep(3000)
        println("[IDE-AGENT] Video recording stopped. File: $videoFile")
    }

    /**
     * Run a command inside the container.
     */
    fun runInContainer(
        vararg args: String,
        timeoutSeconds: Long = 30,
        extraEnvVars: Map<String, String> = emptyMap(),
    ): ProcessResult = scope.runInContainer(containerId, args.toList(), timeoutSeconds, extraEnvVars)

    companion object {
        private const val LOG_PREFIX = "IDE-AGENT"
        private const val IMAGE_NAME = "mcp-steroid-ide-test"
        private const val IDEA_BIN_DIR = "/opt/idea/bin"
        private const val IDEA_VMOPTIONS_PATH = "$IDEA_BIN_DIR/idea64.vmoptions"

        // Explicit IDE directory paths inside the container
        const val IDE_CONFIG_DIR = "/home/agent/ide-config"
        const val IDE_SYSTEM_DIR = "/home/agent/ide-system"
        const val IDE_LOG_DIR = "/home/agent/ide-log"
        const val IDE_PLUGINS_DIR = "/home/agent/ide-plugins"

        private const val CONTAINER_VIDEO_DIR = "/home/agent/video"
        private const val CONTAINER_SCREENSHOTS_DIR = "/home/agent/screenshots"

        private val RUN_DIR_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd'Z'HH-mm-ss-SSS")

        /**
         * Build and start an IDE Docker container.
         * Removes any existing container with the same name before starting.
         *
         * All IDE directories, video, and screenshots are mounted to a timestamped
         * run directory under testOutputDir.
         *
         * @param containerName Stable name for the container (survives test runs for debugging)
         * @param pluginZipPath Path to the built plugin .zip
         * @param ideaArchivePath Path to the downloaded IntelliJ IDEA .tar.gz
         * @param testProjectDir Path to the test project directory
         * @param dockerDir Path to the docker directory containing Dockerfile and entrypoint.sh
         * @param testOutputDir Root output directory; a timestamped run subfolder is created
         */
        fun start(
            containerName: String,
            pluginZipPath: File,
            ideaArchivePath: File,
            testProjectDir: File,
            dockerDir: File,
            testOutputDir: File,
        ): IdeContainerSession {
            require(pluginZipPath.isFile) { "Plugin zip not found: $pluginZipPath" }
            require(ideaArchivePath.isFile) { "IDEA archive not found: $ideaArchivePath" }
            require(testProjectDir.isDirectory) { "Test project not found: $testProjectDir" }
            require(dockerDir.isDirectory) { "Docker dir not found: $dockerDir" }

            // Create timestamped run directory
            val timestamp = RUN_DIR_FORMATTER.format(LocalDateTime.now())
            val runDir = File(testOutputDir, "run-$timestamp")
            runDir.mkdirs()

            // Create all mount-point subdirectories
            val videoDir = File(runDir, "video").apply { mkdirs() }
            val screenshotDir = File(runDir, "screenshots").apply { mkdirs() }
            val configDir = File(runDir, "ide-config").apply { mkdirs() }
            val systemDir = File(runDir, "ide-system").apply { mkdirs() }
            val logDir = File(runDir, "ide-log").apply { mkdirs() }
            val pluginsDir = File(runDir, "ide-plugins").apply { mkdirs() }

            println("[$LOG_PREFIX] Run directory: $runDir")

            val contextDir = assembleDockerContext(dockerDir, ideaArchivePath, testProjectDir)
            val driver = DockerDriver(contextDir, LOG_PREFIX, emptyList())

            buildImage(driver, contextDir)
            val containerId = startContainer(
                driver, containerName, contextDir,
                videoDir = videoDir,
                screenshotDir = screenshotDir,
                configDir = configDir,
                systemDir = systemDir,
                logDir = logDir,
                pluginsDir = pluginsDir,
            )

            // Write containerId file
            File(runDir, "containerId").writeText(containerId)

            // Write generated vmoptions into the container
            writeVmOptionsToContainer(driver, containerId, contextDir)

            // Deploy plugin into the container's plugins directory
            deployPluginToContainer(driver, containerId, pluginZipPath)

            println("[$LOG_PREFIX] Container started: name=$containerName id=$containerId")
            println("[$LOG_PREFIX] Run directory: $runDir")

            return IdeContainerSession(driver, containerId, containerName, runDir)
        }

        /**
         * Generate IDEA VM options content for running inside the Docker container.
         */
        private fun generateVmOptions(): String = buildString {
            appendLine("-Xmx2g")
            appendLine("-Xms512m")

            appendLine("# Redirect IDE directories to explicit paths")
            appendLine("-Didea.config.path=$IDE_CONFIG_DIR")
            appendLine("-Didea.system.path=$IDE_SYSTEM_DIR")
            appendLine("-Didea.log.path=$IDE_LOG_DIR")
            appendLine("-Didea.plugins.path=$IDE_PLUGINS_DIR")

            appendLine("# MCP Steroid plugin configuration")
            appendLine("-Dmcp.steroid.server.host=0.0.0.0")
            appendLine("-Dmcp.steroid.server.port=6315")
            appendLine("-Dmcp.steroid.review.mode=NEVER")
            appendLine("-Dmcp.steroid.updates.enabled=false")
            appendLine("-Dmcp.steroid.analytics.enabled=false")
            appendLine("-Dmcp.steroid.idea.description.enabled=false")

            appendLine("# Skip EULA, consent dialogs, and onboarding")
            appendLine("-Djb.consents.confirmation.enabled=false")
            appendLine("-Djb.privacy.policy.text=<!--999.999-->")
            appendLine("-Djb.privacy.policy.ai.assistant.text=<!--999.999-->")
            appendLine("-Dmarketplace.eula.reviewed.and.accepted=true")
            appendLine("-Dwriterside.eula.reviewed.and.accepted=true")
            appendLine("-Didea.initially.ask.config=never")
            appendLine("-Dide.newUsersOnboarding=false")

            appendLine("# Suppress telemetry and update checks")
            appendLine("-Didea.suppress.statistics.report=true")
            appendLine("-Didea.local.statistics.without.report=true")
            appendLine("-Dfeature.usage.event.log.send.on.ide.close=false")
            appendLine("-Dide.enable.notification.trace.data.sharing=false")
            appendLine("-Didea.updates.url=http://127.0.0.1")
            appendLine("-Dide.do.not.disable.paid.plugins.on.startup=true")
        }

        /**
         * Write the generated vmoptions file into the container at the IDEA install location.
         */
        private fun writeVmOptionsToContainer(driver: DockerDriver, containerId: String, workDir: File) {
            val vmoptionsContent = generateVmOptions()
            val tempFile = File(workDir, "idea64.vmoptions")
            tempFile.writeText(vmoptionsContent)

            println("[$LOG_PREFIX] Writing vmoptions to container: $IDEA_VMOPTIONS_PATH")
            driver.copyToContainer(containerId, tempFile, IDEA_VMOPTIONS_PATH)
            tempFile.delete()
        }

        /**
         * Deploy the MCP Steroid plugin into the running container.
         * Copies the plugin zip and extracts it into the plugins directory.
         */
        private fun deployPluginToContainer(driver: DockerDriver, containerId: String, pluginZipPath: File) {
            val containerTempPath = "/tmp/plugin.zip"
            println("[$LOG_PREFIX] Deploying plugin to container: $IDE_PLUGINS_DIR")

            driver.copyToContainer(containerId, pluginZipPath, containerTempPath)
            driver.runInContainer(
                containerId,
                listOf("mkdir", "-p", IDE_PLUGINS_DIR),
                timeoutSeconds = 10,
            )
            driver.runInContainer(
                containerId,
                listOf("unzip", "-o", containerTempPath, "-d", IDE_PLUGINS_DIR),
                timeoutSeconds = 30,
            )
            driver.runInContainer(
                containerId,
                listOf("rm", containerTempPath),
                timeoutSeconds = 10,
            )
        }

        /**
         * Assemble the Docker build context directory.
         * Copies docker resources (Dockerfile, entrypoint.sh) as files,
         * symlinks large artifacts (IDEA archive) to avoid redundant copies.
         * Plugin is deployed separately during container run.
         */
        private fun assembleDockerContext(
            dockerDir: File,
            ideaArchivePath: File,
            testProjectDir: File,
        ): File {
            val contextDir = createTempDir(LOG_PREFIX.lowercase())
            println("[$LOG_PREFIX] Build context: $contextDir")

            // Copy docker resource files (Dockerfile, entrypoint.sh)
            dockerDir.listFiles()?.filter { it.isFile }?.forEach { file ->
                file.copyTo(File(contextDir, file.name), overwrite = true)
            }

            // Symlink large IDEA archive to avoid copying ~1GB file
            Files.createSymbolicLink(
                File(contextDir, "idea.tar.gz").toPath(),
                ideaArchivePath.toPath(),
            )

            testProjectDir.copyRecursively(File(contextDir, "test-project"), overwrite = true)

            return contextDir
        }

        private fun buildImage(driver: DockerDriver, contextDir: File) {
            driver.buildDockerImage(
                imageName = IMAGE_NAME,
                dockerfilePath = File(contextDir, "Dockerfile"),
                timeoutSeconds = 900,
            )
        }

        private fun startContainer(
            driver: DockerDriver,
            containerName: String,
            workDir: File,
            videoDir: File,
            screenshotDir: File,
            configDir: File,
            systemDir: File,
            logDir: File,
            pluginsDir: File,
        ): String {
            // Remove existing container with the same name (from previous run)
            println("[$LOG_PREFIX] Removing previous container '$containerName' if exists...")
            driver.processRunner.run(
                listOf("docker", "rm", "-f", containerName),
                description = "Remove previous container $containerName",
                workingDir = workDir,
                timeoutSeconds = 10,
            )

            val dockerRunCmd = buildList {
                add("docker")
                add("run")
                add("-d")
                add("--name")
                add(containerName)
                add("--shm-size=2g")
                add("--memory=4g")

                // Mount all IDE directories to host for inspection
                fun mount(hostDir: File, containerDir: String) {
                    add("-v")
                    add("${hostDir.absolutePath}:$containerDir")
                }
                mount(videoDir, CONTAINER_VIDEO_DIR)
                mount(screenshotDir, CONTAINER_SCREENSHOTS_DIR)
                mount(configDir, IDE_CONFIG_DIR)
                mount(systemDir, IDE_SYSTEM_DIR)
                mount(logDir, IDE_LOG_DIR)
                mount(pluginsDir, IDE_PLUGINS_DIR)

                // Tell entrypoint to write video to the mounted path
                add("-e")
                add("VIDEO_OUTPUT=$CONTAINER_VIDEO_DIR/recording.mp4")
                add(IMAGE_NAME)
            }

            val result = driver.processRunner.run(
                dockerRunCmd,
                description = "Start IDE container '$containerName'",
                workingDir = workDir,
                timeoutSeconds = 30,
            )

            val containerId = result.output.trim()
            if (result.exitCode != 0 || containerId.isEmpty()) {
                error("Failed to start IDE container: ${result.stderr}")
            }
            return containerId
        }

        private fun createTempDir(prefix: String): File {
            val tempDir = File(System.getProperty("java.io.tmpdir"), "docker-$prefix-${System.currentTimeMillis()}")
            tempDir.mkdirs()
            return tempDir
        }
    }
}
