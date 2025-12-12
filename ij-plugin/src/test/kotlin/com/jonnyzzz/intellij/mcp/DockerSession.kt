/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.intellij.mcp

import com.intellij.openapi.Disposable
import java.io.File

/**
 * Base class for managing CLI sessions running inside Docker containers.
 * Provides common functionality for building images, starting/stopping containers,
 * and running commands.
 */
abstract class DockerSession(
    protected val containerId: String,
    protected val workDir: File,
) : AutoCloseable, Disposable {

    /** Name used for logging */
    protected abstract val logPrefix: String

    /** Process runner with secret filtering. Subclasses can add secrets via addSecretFilter() */
    protected val processRunner = ProcessRunner()

    /**
     * Run an arbitrary command inside the Docker container.
     */
    fun runRaw(vararg args: String, timeoutSeconds: Long = 120): ProcessResult {
        return runInContainer(args.toList(), timeoutSeconds)
    }

    protected fun runInContainer(
        args: List<String>,
        timeoutSeconds: Long,
        enableDebugEnv: Boolean = false,
        extraEnvVars: Map<String, String> = emptyMap()
    ): ProcessResult {
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
            // Add extra environment variables
            extraEnvVars.forEach { (key, value) ->
                add("-e")
                add("$key=$value")
            }
            // Enable debug environment variables
            if (enableDebugEnv) {
                add("-e")
                add("DEBUG=*")
            }
            add(containerId)
            add("bash")
            add("-c")
            add(shellCommand)
        }

        return processRunner.run(
            command,
            description = "In container: ${args.joinToString(" ")}",
            workingDir = workDir,
            timeoutSeconds = timeoutSeconds,
            logPrefix = logPrefix
        )
    }

    override fun dispose() {
        close()
    }

    override fun close() {
        println("[$logPrefix] Stopping and removing container: $containerId")
        try {
            ProcessRunner.run(
                listOf("docker", "kill", containerId),
                description = "kill container",
                workingDir = workDir,
                timeoutSeconds = 10,
                logPrefix = "DOCKER"
            )

            ProcessRunner.run(
                listOf("docker", "rm", "-f", containerId),
                description = "Remove container",
                workingDir = workDir,
                timeoutSeconds = 5,
                logPrefix = "DOCKER"
            )

            println("[$logPrefix] Container removed successfully")

            workDir.deleteRecursively()
            println("[$logPrefix] Temp directory cleaned up: $workDir")
        } catch (e: Exception) {
            println("[$logPrefix] Failed to clean up: ${e.message}")
        }
    }

    companion object {
        fun createTempDirectory(prefix: String): File {
            val tempDir = File(System.getProperty("java.io.tmpdir"), "$prefix-${System.currentTimeMillis()}")
            tempDir.mkdirs()
            return tempDir
        }

        fun buildDockerImage(imageName: String, dockerfilePath: File, logPrefix: String, timeoutSeconds: Long = 300) {
            val result = ProcessRunner.run(
                listOf("docker", "build", "-t", imageName, "."),
                description = "Build Docker image $imageName",
                workingDir = dockerfilePath.parentFile,
                timeoutSeconds = timeoutSeconds,
                logPrefix = "DOCKER-BUILD"
            )

            if (result.exitCode != 0) {
                throw IllegalStateException("Failed to build Docker image. Exit code: ${result.exitCode}\n${result.stderr}")
            }

            println("[$logPrefix] Docker image built successfully")
        }

        fun startContainer(
            imageName: String,
            workDir: File,
            logPrefix: String,
        ): String {
            val command = buildList {
                add("docker")
                add("run")
                add("-d")
                add("--add-host=host.docker.internal:host-gateway")
                add(imageName)
            }

            val result = ProcessRunner.run(
                command,
                description = "Start container from $imageName",
                workingDir = workDir,
                timeoutSeconds = 30,
                logPrefix = "DOCKER"
            )

            val containerId = result.output.trim()
            if (result.exitCode != 0 || containerId.isEmpty()) {
                throw IllegalStateException("Failed to start Docker container: ${result.stderr}")
            }

            println("[$logPrefix] Container started: $containerId")
            return containerId
        }
    }
}
