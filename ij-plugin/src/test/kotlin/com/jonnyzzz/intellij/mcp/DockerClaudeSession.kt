/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.intellij.mcp

import com.intellij.openapi.Disposable
import java.io.File

/**
 * Manages a Claude CLI session running inside a Docker container.
 * This provides complete isolation from the local system, preventing
 * MCP server registrations from affecting the local Claude config.
 *
 * All output is logged with clear prefixes for debugging.
 */
class DockerClaudeSession(
    private val containerId: String,
    private val workDir: File,
    private val apiKey: String?,
    private val verbose: Boolean = false
) : AutoCloseable, Disposable {

    /**
     * Run an arbitrary command inside the Docker container.
     * All output is logged to stdout with prefixes.
     */
    fun runRaw(vararg args: String, timeoutSeconds: Long = 120): ProcessResult {
        return runInContainer(args.toList(), timeoutSeconds)
    }

    /**
     * Run a claude command inside the Docker container.
     * All output is logged to stdout with prefixes.
     */
    fun run(vararg args: String, timeoutSeconds: Long = 120): ProcessResult {
        val claudeArgs = buildList {
            add("claude")
            // Always enable debug for MCP info
            add("--debug")
            if (verbose) add("--verbose")
            addAll(args.toList())
        }
        return runInContainer(claudeArgs, timeoutSeconds)
    }

    private fun runInContainer(args: List<String>, timeoutSeconds: Long): ProcessResult {
        // Escape the command for shell execution inside docker
        val shellCommand = args.joinToString(" ") { arg ->
            if (arg.contains(" ") || arg.contains("\"") || arg.contains("\n")) {
                "'" + arg.replace("'", "'\\''") + "'"
            } else {
                arg
            }
        }

        val command = buildList {
            add("docker")
            add("exec")
            // Only pass API key if provided
            if (apiKey != null) {
                add("-e")
                add("ANTHROPIC_API_KEY=$apiKey")
            }
            add(containerId)
            add("bash")
            add("-c")
            add(shellCommand)
        }

        return ProcessRunner.run(
            command,
            description = "In container: ${args.joinToString(" ")}",
            workingDir = workDir,
            timeoutSeconds = timeoutSeconds,
            logPrefix = "DOCKER-CLAUDE"
        )
    }

    override fun dispose() {
        close()
    }

    override fun close() {
        println("[DOCKER-CLAUDE] Stopping and removing container: $containerId")
        try {
            // Stop the container
            ProcessRunner.run(
                listOf("docker", "kill", containerId),
                description = "kill container",
                workingDir = workDir,
                timeoutSeconds = 10,
                logPrefix = "DOCKER"
            )

            // Remove the container
            ProcessRunner.run(
                listOf("docker", "rm", "-f", containerId),
                description = "Remove container",
                workingDir = workDir,
                timeoutSeconds = 5,
                logPrefix = "DOCKER"
            )

            println("[DOCKER-CLAUDE] Container removed successfully")

            // Clean up temp directory
            workDir.deleteRecursively()
            println("[DOCKER-CLAUDE] Temp directory cleaned up: $workDir")
        } catch (e: Exception) {
            println("[DOCKER-CLAUDE] Failed to clean up: ${e.message}")
        }
    }

    companion object {
        private const val IMAGE_NAME = "claude-cli-test:latest"
        private val DOCKERFILE_PATH = File("src/test/docker/claude-cli/Dockerfile")

        /**
         * Create a new Docker-based Claude session.
         * Builds the Docker image if needed and starts a new container.
         *
         * @param apiKey Optional API key. Required for Claude commands that need API access
         *               (e.g., --print), but not needed for `mcp` subcommands.
         * @param verbose Enable verbose logging
         */
        fun create(apiKey: String? = null, verbose: Boolean = true): DockerClaudeSession {
            // Create temp directory for this session
            val tempDir = createTempDirectory()
            println("[DOCKER-CLAUDE] Creating new session in temp dir: $tempDir")

            // Build the Docker image
            buildDockerImage()

            // Start the container
            val containerId = startContainer(tempDir)

            println("[DOCKER-CLAUDE] Session created in container: $containerId")
            return DockerClaudeSession(containerId, tempDir, apiKey, verbose)
        }

        private fun createTempDirectory(): File {
            val tempDir = File(System.getProperty("java.io.tmpdir"), "claude-test-${System.currentTimeMillis()}")
            tempDir.mkdirs()
            return tempDir
        }

        private fun buildDockerImage() {
            // Find the dockerfile

            val result = ProcessRunner.run(
                listOf("docker", "build", "-t", IMAGE_NAME, "."),
                description = "Build Docker image $IMAGE_NAME",
                workingDir = DOCKERFILE_PATH.parentFile,
                timeoutSeconds = 300,
                logPrefix = "DOCKER-BUILD"
            )

            if (result.exitCode != 0) {
                throw IllegalStateException("Failed to build Docker image. Exit code: ${result.exitCode}\n${result.stderr}")
            }

            println("[DOCKER-CLAUDE] Docker image built successfully")
        }

        private fun startContainer(workDir: File): String {
            val result = ProcessRunner.run(
                listOf(
                    "docker", "run", "-d",
                    "--network=host",
                    IMAGE_NAME
                ),
                description = "Start container from $IMAGE_NAME",
                workingDir = workDir,
                timeoutSeconds = 30,
                logPrefix = "DOCKER"
            )

            val containerId = result.output.trim()
            if (result.exitCode != 0 || containerId.isEmpty()) {
                throw IllegalStateException("Failed to start Docker container: ${result.stderr}")
            }

            println("[DOCKER-CLAUDE] Container started: $containerId")
            return containerId
        }
    }
}