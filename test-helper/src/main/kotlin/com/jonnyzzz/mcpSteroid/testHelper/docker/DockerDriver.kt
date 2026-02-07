/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.testHelper.docker

import com.jonnyzzz.mcpSteroid.testHelper.CloseableStack
import com.jonnyzzz.mcpSteroid.testHelper.ProcessResult
import com.jonnyzzz.mcpSteroid.testHelper.ProcessRunner
import com.jonnyzzz.mcpSteroid.testHelper.assertExitCode
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatter.ofPattern
import kotlin.collections.component1
import kotlin.collections.component2

class DockerDriver(
    val workDir: File,
    val logPrefix: String,
    val secretPatterns: List<String> = emptyList(),
    val environmentVariables: Map<String, String> = emptyMap(),
    val guestWorkDir: String? = null,
) {
    fun withGuestWorkDir(guestWorkDir: String): DockerDriver {
        return DockerDriver(workDir, logPrefix, secretPatterns, environmentVariables, guestWorkDir)
    }

    fun withSecretPattern(secretPattern: String): DockerDriver {
        return DockerDriver(workDir, logPrefix, (secretPatterns + secretPattern).distinct(), environmentVariables, guestWorkDir)
    }

    fun withEnv(key: String, value: String): DockerDriver {
        return DockerDriver(workDir, logPrefix, secretPatterns, (environmentVariables + (key to value)).toSortedMap(), guestWorkDir)
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
        lifetime: CloseableStack,
        imageName: String,
        extraEnvVars: Map<String, String> = emptyMap(),
        volumes: List<ContainerVolume> = emptyList(),
        ports: List<ContainerPort> = emptyList(),
    ): String {
        val command = buildList {
            add("docker")
            add("run")
            add("-d")
            add("--add-host=host.docker.internal:host-gateway")
            (environmentVariables + extraEnvVars).forEach { (key, value) ->
                add("-e")
                add("$key=$value")
            }

            volumes.forEach { v ->
                add("-v")
                add("${v.host.absolutePath}:${v.guest}:${v.mode}")
            }

            ports.forEach { p ->
                add("-p")
                add("0:${p.containerPort}")
            }

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
        lifetime.registerCleanupAction {
            println("[$logPrefix] Stopping and removing container: $containerId")
            killContainer(containerId)
        }

        return containerId
    }

    /**
     * Query the host port mapped to a container port.
     * Docker output format: "0.0.0.0:52134" or "[::]:52134"
     */
    fun queryMappedPort(containerId: String, containerPort: Int): Int {
        val result = processRunner.run(
            listOf("docker", "port", containerId, "$containerPort/tcp"),
            description = "Query host port for $containerPort",
            workingDir = workDir,
            timeoutSeconds = 5,
        )
        if (result.exitCode != 0) {
            error("Failed to query mapped port for $containerPort: ${result.stderr}")
        }
        // Parse "0.0.0.0:52134" or "[::]:52134" — take the last colon-separated part
        return result.output.trim().lines().first().substringAfterLast(':').toIntOrNull()
            ?: error("Failed to parse host port from: ${result.output}")
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

    fun copyToContainer(
        containerId: String,
        localPath: File,
        containerPath: String,
    ) {
        require(localPath.exists()) { "Local path does not exist: $localPath" }
        processRunner.run(
            listOf("docker", "cp", localPath.absolutePath, "$containerId:$containerPath"),
            description = "Copy ${localPath.name} to container:$containerPath",
            workingDir = workDir,
            timeoutSeconds = 30L,
        ).assertExitCode(0)
    }

    fun copyFromContainer(
        containerId: String,
        containerPath: String,
        localPath: File,
    ) {
        localPath.parentFile?.mkdirs()
        processRunner.run(
            listOf("docker", "cp", "$containerId:$containerPath", localPath.absolutePath),
            description = "Copy container:$containerPath to ${localPath.name}",
            workingDir = workDir,
            timeoutSeconds = 30L,
        ).assertExitCode(0)
    }

    fun writeFileInContainer(
        containerId: String,
        containerPath: String,
        content: String,
        executable: Boolean = false,
    ) {
        // Ensure parent directory exists
        val parentDir = containerPath.substringBeforeLast('/')
        if (parentDir.isNotEmpty()) {
            runInContainer(containerId, listOf("mkdir", "-p", parentDir), timeoutSeconds = 5).assertExitCode(0)
        }

        runInContainer(
            containerId,
            listOf("bash", "-c", "cat > $containerPath << 'FILE_EOF'\n$content\nFILE_EOF"),
            timeoutSeconds = 5,
        ).assertExitCode(0)

        if (executable) {
            runInContainer(containerId, listOf("chmod", "+x", containerPath), timeoutSeconds = 5).assertExitCode(0)
        }
    }

    fun runInContainer(
        containerId: String,
        args: List<String>,
        workingDir: String? = null,
        timeoutSeconds: Long,
        extraEnvVars: Map<String, String> = emptyMap(),
        detach: Boolean = false,
    ): ProcessResult {
        val shellCommand = escapeShellArgs(args)

        val command = buildList {
            add("docker")
            add("exec")
            if (detach) add("--detach")
            (environmentVariables + extraEnvVars).forEach { (key, value) ->
                add("-e")
                add("$key=$value")
            }
            if (workingDir != null) {
                add("-w")
                add(workingDir)
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

    fun runInContainerDetached(
        containerId: String,
        args: List<String>,
        workingDir: String? = null,
        extraEnvVars: Map<String, String> = emptyMap(),
    ): RunningContainerProcess {
        val timestamp = ofPattern("yyyyMMdd-HHmmss-SSS").format(LocalDateTime.now())
        val name = args.first().substringAfterLast("/")
        val logDir = "/tmp/run-$timestamp-$name"

        // Build the wrapper script that runs the real command,
        // captures its PID, and redirects output to files
        val innerCommand = escapeShellArgs(args)
        val wrapperScript = buildString {
            appendLine("#!/bin/bash")
            appendLine("$innerCommand >$logDir/stdout.log 2>$logDir/stderr.log &")
            appendLine("_PID=\$!")
            appendLine("echo \$_PID > $logDir/pid")
            appendLine("wait \$_PID")
            appendLine("echo \$? > $logDir/exitcode")
        }

        // Write the wrapper script into the container
        val scriptPath = "$logDir/run.sh"
        writeFileInContainer(containerId, scriptPath, wrapperScript, executable = true)

        // Run the wrapper script detached
        val result = runInContainer(
            containerId,
            listOf("bash", scriptPath),
            workingDir = workingDir,
            timeoutSeconds = 10,
            extraEnvVars = extraEnvVars,
            detach = true,
        )

        if (result.exitCode != 0) {
            error("Failed to start detached process '$name': ${result.stderr}")
        }

        println("[$logPrefix] Detached process '$name' started, stdout/stderr at $logDir")
        return RunningContainerProcess(this, containerId, name, logDir)
    }

    private fun escapeShellArgs(args: List<String>): String =
        args.joinToString(" ") { arg ->
            if (arg.contains(" ") || arg.contains("\"") || arg.contains("\n") || arg.contains("'")) {
                "'" + arg.replace("'", "'\\''") + "'"
            } else {
                arg
            }
        }
}
