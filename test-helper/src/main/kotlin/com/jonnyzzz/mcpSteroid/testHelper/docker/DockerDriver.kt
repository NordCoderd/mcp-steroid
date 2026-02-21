/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.testHelper.docker

import com.jonnyzzz.mcpSteroid.testHelper.CloseableStack
import com.jonnyzzz.mcpSteroid.testHelper.process.ProcessResult
import com.jonnyzzz.mcpSteroid.testHelper.process.ProcessRunRequest
import com.jonnyzzz.mcpSteroid.testHelper.process.ProcessRunner
import com.jonnyzzz.mcpSteroid.testHelper.process.assertExitCode
import com.jonnyzzz.mcpSteroid.testHelper.process.builder
import com.jonnyzzz.mcpSteroid.testHelper.process.runProcess
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
        return DockerDriver(
            workDir,
            logPrefix,
            (secretPatterns + secretPattern).distinct(),
            environmentVariables,
            guestWorkDir
        )
    }

    fun withEnv(key: String, value: String): DockerDriver {
        return DockerDriver(
            workDir,
            logPrefix,
            secretPatterns,
            (environmentVariables + (key to value)).toSortedMap(),
            guestWorkDir
        )
    }

    val processRunner get() = ProcessRunner(logPrefix, secretPatterns.toList())

    /**
     * Build a Docker image and return its content-addressable image ID (sha256:...).
     *
     * The image is also tagged with [imageName] for human readability (`docker images`),
     * but callers should use the returned image ID for [startContainer] to avoid
     * naming collisions in concurrent test runs.
     *
     * @param buildArgs Extra `--build-arg KEY=VALUE` entries (e.g. `BASE_IMAGE` for derived images)
     * @return The image ID in `sha256:<hex>` format
     */
    fun buildDockerImage(
        imageName: String,
        dockerfilePath: File,
        timeoutSeconds: Long,
        buildArgs: Map<String, String> = emptyMap(),
    ): String {
        require(dockerfilePath.exists() && dockerfilePath.isFile) { "File does not exist: $dockerfilePath" }

        val nowDate = DateTimeFormatter.ISO_DATE.format(LocalDateTime.now())
        val iidFile = kotlin.io.path.createTempFile("docker-iid", ".txt").toFile()
        try {
            val command = buildList {
                add("docker"); add("build")
                add("-t"); add(imageName)
                add("--iidfile"); add(iidFile.absolutePath)
                add("--build-arg"); add("CACHE_BUST=$nowDate")
                for ((k, v) in buildArgs) {
                    add("--build-arg"); add("$k=$v")
                }
                add(".")
            }

            val result = processRunner.runProcess(
                ProcessRunRequest.builder()
                    .command(command)
                    .description(description = "Build Docker image $imageName")
                    .workingDir(workingDir = dockerfilePath.parentFile)
                    .timeoutSeconds(timeoutSeconds = timeoutSeconds)
                    .build()
            )

            result.assertExitCode(0) { "Failed to build Docker image.\n${result.stderr}" }

            val imageId = iidFile.readText().trim()
            require(imageId.startsWith("sha256:")) { "Unexpected image ID format from --iidfile: $imageId" }
            println("[$logPrefix] Docker image built: $imageName ($imageId)")
            return imageId
        } finally {
            iidFile.delete()
        }
    }

    fun startContainer(
        lifetime: CloseableStack,
        imageName: String,
        extraEnvVars: Map<String, String> = emptyMap(),
        volumes: List<ContainerVolume> = emptyList(),
        ports: List<ContainerPort> = emptyList(),
        cmd: List<String> = emptyList(),
        autoRemove: Boolean = false,
    ): String {
        val command = buildList {
            add("docker")
            add("run")
            add("-d")
            if (autoRemove) add("--rm")
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

            // Add container command if specified
            addAll(cmd)
        }

        val result = ProcessRunRequest.builder()
                .command(command)
                .description("Start container from $imageName")
                .workingDir(workDir)
                .runProcess(processRunner)
            .assertExitCode(0) {
                "Failed to start Docker container: $stderr"
            }

        val containerId = result.stdout.trim()
        if (containerId.isEmpty()) {
            throw IllegalStateException("Failed to start Docker container: ${result.stderr}")
        }

        println("[$logPrefix] Container started: $containerId")

        // Register normal cleanup action
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
        val result = ProcessRunRequest.builder()
            .command("docker", "port", containerId, "$containerPort/tcp")
            .description("Query host port for $containerPort")
            .workingDir(workDir)
            .timeoutSeconds(5)
            .quietly()
            .runProcess(processRunner)
            .assertExitCode(0) { "Failed to query mapped port for $containerPort: $stderr" }

        // Parse "0.0.0.0:52134" or "[::]:52134" — take the last colon-separated part
        return result.stdout.trim().lines().first().substringAfterLast(':').toIntOrNull()
            ?: error("Failed to parse host port from: ${result.stdout}")
    }

    /**
     * Query bridge-network container IP (for example 172.17.x.x).
     * Returns null when inspect output does not contain an address.
     */
    fun queryContainerIp(containerId: String): String? {
        val result = ProcessRunRequest.builder()
            .command(
                "docker",
                "inspect",
                "-f",
                "{{range .NetworkSettings.Networks}}{{.IPAddress}} {{end}}",
                containerId
            )
            .description("Query container IP")
            .workingDir(workDir)
            .timeoutSeconds(5)
            .quietly()
            .runProcess(processRunner)
            .assertExitCode(0) { "Failed to query container IP: $stderr" }

        return result.stdout
            .trim()
            .split(Regex("\\s+"))
            .firstOrNull { it.isNotBlank() }
    }

    fun killContainer(containerId: String) {
        ProcessRunRequest.builder()
            .command("docker", "kill", containerId)
            .description("kill container")
            .workingDir(workDir)
            .timeoutSeconds(10)
            .runProcess(processRunner)

        ProcessRunRequest.builder()
            .command("docker", "rm", "-f", containerId)
            .description("Remove container")
            .workingDir(workingDir = workDir)
            .timeoutSeconds(timeoutSeconds = 5)
            .quietly(true)
            .runProcess(processRunner)

        println("[$logPrefix] Container removed successfully")
    }

    fun copyToContainer(
        containerId: String,
        localPath: File,
        containerPath: String,
    ) {
        require(localPath.exists()) { "Local path does not exist: $localPath" }
        ProcessRunRequest.builder()
            .command("docker", "cp", localPath.absolutePath, "$containerId:$containerPath")
            .description("Copy ${localPath.name} to container:$containerPath")
            .workingDir(workDir)
            .timeoutSeconds(30L)
            .quietly()
            .runProcess(processRunner)
            .assertExitCode(0) { "Failed to copy to container: $localPath: $stderr" }
    }

    fun copyFromContainer(
        containerId: String,
        containerPath: String,
        localPath: File,
    ) {
        localPath.parentFile?.mkdirs()
        ProcessRunRequest.builder()
            .command("docker", "cp", "$containerId:$containerPath", localPath.absolutePath)
            .description("Copy container:$containerPath to ${localPath.name}")
            .workingDir(workDir)
            .timeoutSeconds(30L)
            .quietly()
            .runProcess(processRunner)
            .assertExitCode(0) { "Failed to copy to container: $localPath: $stderr" }
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
            runInContainer(
                containerId,
                listOf("mkdir", "-p", parentDir),
                timeoutSeconds = 5,
                quietly = true
            ).assertExitCode(0)
        }

        runInContainer(
            containerId,
            listOf("bash", "-c", "cat > $containerPath << 'FILE_EOF'\n$content\nFILE_EOF"),
            timeoutSeconds = 5,
            quietly = true,
        ).assertExitCode(0)

        if (executable) {
            runInContainer(
                containerId,
                listOf("chmod", "+x", containerPath),
                timeoutSeconds = 5,
                quietly = true
            ).assertExitCode(0)
        }
    }

    fun runInContainer(
        containerId: String,
        args: List<String>,
        workingDir: String? = null,
        timeoutSeconds: Long,
        extraEnvVars: Map<String, String> = emptyMap(),
        detach: Boolean = false,
        quietly: Boolean = false,
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

        return ProcessRunRequest.builder()
            .command(command)
            .description("In container: ${args.joinToString(" ")}")
            .workingDir(workDir)
            .timeoutSeconds(timeoutSeconds)
            .quietly(quietly)
            .runProcess(processRunner)
    }

    fun runInContainerDetached(
        containerId: String,
        args: List<String>,
        workingDir: String? = null,
        extraEnvVars: Map<String, String> = emptyMap(),
    ): DetachedContainerProcess {
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

        result.assertExitCode(0) { "Failed to start detached process '$name': ${result.stderr}" }

        println("[$logPrefix] Detached process '$name' started, stdout/stderr at $logDir")
        return DetachedContainerProcess(name = name, logDir = logDir)
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

data class DetachedContainerProcess(
    val name: String,
    val logDir: String,
)
