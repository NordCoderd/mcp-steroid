/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.testHelper

import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Base class for managing CLI sessions running inside Docker containers.
 * Provides common functionality for building images, starting/stopping containers,
 * and running commands.
 */
interface DockerSession {
    fun runInContainer(
        vararg args: String,
        timeoutSeconds: Long = 30,
        extraEnvVars: Map<String, String> = emptyMap()
    ): ProcessResult

    companion object
}

interface AiAgentSession {
    /**
     * Run codex exec for non-interactive mode.
     */
    fun runPrompt(
        prompt: String,
        timeoutSeconds: Long = 120
    ): ProcessResult
}

class DockerSessionScope(
    val workDir: File,
    val logPrefix: String,
    secretPatterns: List<String>,
) {
    val processRunner = ProcessRunner(logPrefix, secretPatterns)

    fun buildDockerImage(
        imageName: String,
        dockerfilePath: File,
        timeoutSeconds: Long
    ) {
        require(dockerfilePath.exists() && dockerfilePath.isFile) { "File does not exist: $dockerfilePath" }


        val nowDate = DateTimeFormatter.ISO_DATE.format(LocalDateTime.now())
        val result = processRunner.run(
            listOf("docker", "build", "-t", imageName, "--build-arg", "CACHE_BUST=$nowDate", "."),
            description = "Build Docker image $imageName",
            workingDir = dockerfilePath.parentFile,
            timeoutSeconds = timeoutSeconds,
        )

        if (result.exitCode != 0) {
            throw IllegalStateException("Failed to build Docker image. Exit code: ${result.exitCode}\n${result.stderr}")
        }
        println("[$logPrefix] Docker image built successfully")
    }

    fun startContainer(
        imageName: String,
    ): String {
        val command = buildList {
            add("docker")
            add("run")
            add("-d")
            add("--add-host=host.docker.internal:host-gateway")
            add(imageName)
        }

        val result = processRunner.run(
            command,
            description = "Start container from $imageName",
            workingDir = workDir,
            timeoutSeconds = 30,
        )

        val containerId = result.output.trim()
        if (result.exitCode != 0 || containerId.isEmpty()) {
            throw IllegalStateException("Failed to start Docker container: ${result.stderr}")
        }

        println("[$logPrefix] Container started: $containerId")
        return containerId
    }

    fun killContainer(containerId: String) {
        processRunner.run(
            listOf("docker", "kill", containerId),
            description = "kill container",
            workingDir = workDir,
            timeoutSeconds = 10,
        )

        processRunner.run(
            listOf("docker", "rm", "-f", containerId),
            description = "Remove container",
            workingDir = workDir,
            timeoutSeconds = 5,
        )

        println("[$logPrefix] Container removed successfully")
    }

    fun runInContainer(
        containerId: String,
        args: List<String>,
        timeoutSeconds: Long,
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
        )
    }
}

/**
 * A DockerSession that implements AutoCloseable for cleanup.
 */
class CloseableDockerSession(
    private val scope: DockerSessionScope,
    private val containerId: String,
    private val cleanupActions: List<() -> Unit>,
) : DockerSession, AutoCloseable {

    override fun runInContainer(
        vararg args: String,
        timeoutSeconds: Long,
        extraEnvVars: Map<String, String>
    ): ProcessResult {
        return scope.runInContainer(containerId, args.toList(), timeoutSeconds, extraEnvVars)
    }

    override fun close() {
        cleanupActions.forEach { it() }
    }
}

fun DockerSession.Companion.startDockerSession(
    dockerFileBase: String, //aka codex-cli
    secretPatterns: List<String> = listOf(),
): CloseableDockerSession {
    val cleanupActions = mutableListOf<() -> Unit>()

    val dockerfilePath = File("src/test/docker/$dockerFileBase/Dockerfile")
    require(dockerfilePath.isFile) { "Docker file $dockerfilePath must exist" }

    val logPrefix = dockerFileBase.uppercase().replace("/", "-")
    val workDir = createTempDirectory(logPrefix.lowercase())
    println("[$logPrefix] Creating new session in temp dir: $workDir")
    cleanupActions += {
        workDir.deleteRecursively()
        println("[$logPrefix] Temp directory cleaned up: $workDir")
    }

    val scope = DockerSessionScope(workDir, logPrefix, secretPatterns)
    val imageName = "$dockerFileBase-test"

    //TODO: drop image it it's older than one day
    scope.buildDockerImage(
        imageName = imageName,
        dockerfilePath,
        timeoutSeconds = 600
    )

    //we are not disposing the image
    val containerId = scope.startContainer(imageName)
    cleanupActions += {
        println("[$logPrefix] Stopping and removing container: $containerId")
        scope.killContainer(containerId)
    }

    return CloseableDockerSession(scope, containerId, cleanupActions)
}

private fun createTempDirectory(prefix: String): File {
    val tempDir = File(System.getProperty("java.io.tmpdir"), "docker-$prefix-${System.currentTimeMillis()}")
    tempDir.mkdirs()
    return tempDir
}
