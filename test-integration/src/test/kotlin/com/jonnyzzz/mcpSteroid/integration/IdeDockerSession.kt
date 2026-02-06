/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.integration

import com.jonnyzzz.mcpSteroid.testHelper.CloseableDockerSession
import com.jonnyzzz.mcpSteroid.testHelper.DockerSessionScope
import com.jonnyzzz.mcpSteroid.testHelper.ProcessRunner
import java.io.File

/**
 * Manages a Docker container running IntelliJ IDEA with MCP Steroid plugin.
 * Assembles the Docker build context from separate artifacts and starts the container.
 */
class IdeDockerSession private constructor(
    private val session: CloseableDockerSession,
    private val scope: DockerSessionScope,
    private val containerId: String,
) : AutoCloseable {

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
        scope.runInContainer(
            containerId,
            listOf("scrot", remotePath),
            timeoutSeconds = 10,
        )
        scope.processRunner.run(
            listOf("docker", "cp", "$containerId:$remotePath", localPath.absolutePath),
            description = "Copy screenshot to host",
            workingDir = scope.workDir,
            timeoutSeconds = 10,
        )
    }

    /**
     * Extract video recording from container (stops ffmpeg first).
     */
    fun extractVideo(localPath: File) {
        // Send SIGINT to ffmpeg to finalize the video
        scope.runInContainer(
            containerId,
            listOf("pkill", "-SIGINT", "ffmpeg"),
            timeoutSeconds = 5,
        )
        // Wait for ffmpeg to finish
        Thread.sleep(3000)
        scope.processRunner.run(
            listOf("docker", "cp", "$containerId:/tmp/recording.mp4", localPath.absolutePath),
            description = "Copy video to host",
            workingDir = scope.workDir,
            timeoutSeconds = 30,
        )
    }

    /**
     * Run a command inside the container.
     */
    fun runInContainer(
        vararg args: String,
        timeoutSeconds: Long = 30,
        extraEnvVars: Map<String, String> = emptyMap(),
    ) = scope.runInContainer(containerId, args.toList(), timeoutSeconds, extraEnvVars)

    override fun close() {
        session.close()
    }

    companion object {
        private const val LOG_PREFIX = "IDE-AGENT"
        private const val IMAGE_NAME = "mcp-steroid-ide-test"

        /**
         * Build and start an IDE Docker container.
         *
         * @param pluginZipPath Path to the built plugin .zip
         * @param ideaArchivePath Path to the downloaded IntelliJ IDEA .tar.gz
         * @param testProjectDir Path to the test project directory
         * @param dockerDir Path to the docker/ide-agent directory containing Dockerfile
         * @param videoOutput If set, enables video recording to this path inside container
         */
        fun start(
            pluginZipPath: File,
            ideaArchivePath: File,
            testProjectDir: File,
            dockerDir: File,
            videoOutput: String? = null,
        ): IdeDockerSession {
            require(pluginZipPath.isFile) { "Plugin zip not found: $pluginZipPath" }
            require(ideaArchivePath.isFile) { "IDEA archive not found: $ideaArchivePath" }
            require(testProjectDir.isDirectory) { "Test project not found: $testProjectDir" }
            require(dockerDir.isDirectory) { "Docker dir not found: $dockerDir" }

            val cleanupActions = mutableListOf<() -> Unit>()

            // Create temp build context
            val contextDir = createTempDir(LOG_PREFIX.lowercase())
            println("[$LOG_PREFIX] Build context: $contextDir")
            cleanupActions += {
                contextDir.deleteRecursively()
                println("[$LOG_PREFIX] Build context cleaned up")
            }

            // Assemble build context: copy Dockerfile + entrypoint + vmoptions + artifacts
            dockerDir.listFiles()?.forEach { file ->
                file.copyTo(File(contextDir, file.name), overwrite = true)
            }

            // Copy IDEA archive as idea.tar.gz
            ideaArchivePath.copyTo(File(contextDir, "idea.tar.gz"), overwrite = true)

            // Copy plugin zip
            pluginZipPath.copyTo(File(contextDir, "plugin.zip"), overwrite = true)

            // Copy test project
            val destProject = File(contextDir, "test-project")
            testProjectDir.copyRecursively(destProject, overwrite = true)

            val scope = DockerSessionScope(contextDir, LOG_PREFIX, emptyList())

            // Build image
            scope.buildDockerImage(
                imageName = IMAGE_NAME,
                dockerfilePath = File(contextDir, "Dockerfile"),
                timeoutSeconds = 900, // 15 min for large image
            )

            // Start container with custom flags (no --add-host needed)
            val dockerRunCmd = buildList {
                add("docker")
                add("run")
                add("-d")
                add("--shm-size=2g")
                add("--memory=4g")
                if (videoOutput != null) {
                    add("-e")
                    add("VIDEO_OUTPUT=$videoOutput")
                }
                add(IMAGE_NAME)
            }

            val result = scope.processRunner.run(
                dockerRunCmd,
                description = "Start IDE container",
                workingDir = contextDir,
                timeoutSeconds = 30,
            )

            val containerId = result.output.trim()
            if (result.exitCode != 0 || containerId.isEmpty()) {
                // Clean up on failure
                cleanupActions.asReversed().forEach { it() }
                error("Failed to start IDE container: ${result.stderr}")
            }

            println("[$LOG_PREFIX] Container started: $containerId")
            cleanupActions += {
                println("[$LOG_PREFIX] Stopping container: $containerId")
                scope.killContainer(containerId)
            }

            val session = CloseableDockerSession(scope, containerId, cleanupActions)
            return IdeDockerSession(session, scope, containerId)
        }

        private fun createTempDir(prefix: String): File {
            val tempDir = File(System.getProperty("java.io.tmpdir"), "docker-$prefix-${System.currentTimeMillis()}")
            tempDir.mkdirs()
            return tempDir
        }
    }
}
