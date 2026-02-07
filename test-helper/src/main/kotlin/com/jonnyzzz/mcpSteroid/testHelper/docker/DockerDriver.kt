/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.testHelper.docker

import com.jonnyzzz.mcpSteroid.testHelper.ProcessResult
import com.jonnyzzz.mcpSteroid.testHelper.ProcessRunner
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

class DockerDriver(
    val workDir: File,
    val logPrefix: String,
    val secretPatterns: List<String>,
) {
    fun withSecretPattern(secretPattern: String): DockerDriver {
        return DockerDriver(workDir, logPrefix, (secretPatterns + secretPattern).distinct())
    }

    val processRunner get() = ProcessRunner(logPrefix, secretPatterns.toList())

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