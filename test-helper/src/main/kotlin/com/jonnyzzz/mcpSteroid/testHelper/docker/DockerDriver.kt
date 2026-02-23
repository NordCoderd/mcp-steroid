/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.testHelper.docker

import com.jonnyzzz.mcpSteroid.testHelper.CloseableStack
import com.jonnyzzz.mcpSteroid.testHelper.escapeShellArgs
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
import kotlin.io.path.createTempFile

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
     * Tag an existing Docker image with a new name.
     *
     * @param imageId Source image reference (e.g. `sha256:<hex>` or existing tag)
     * @param tag Target tag (e.g. `mcp-steroid-ide-base-test:latest`)
     */
    fun tagDockerImage(imageId: String, tag: String) {
        ProcessRunRequest.builder()
            .command("docker", "tag", imageId, tag)
            .description("Tag Docker image as $tag")
            .workingDir(workDir)
            .timeoutSeconds(30)
            .runProcess(processRunner)
            .assertExitCode(0) { "Failed to tag Docker image $imageId as $tag: $stderr" }
        println("[$logPrefix] Tagged image $imageId → $tag")
    }

    /**
     * Build a Docker image and return its content-addressable image ID (sha256:...).
     *
     * @param buildArgs Extra `--build-arg KEY=VALUE` entries (e.g. `BASE_IMAGE` for derived images)
     * @return The image ID in `sha256:<hex>` format
     */
    fun buildDockerImage(
        dockerfilePath: File,
        timeoutSeconds: Long,
        buildArgs: Map<String, String> = emptyMap(),
    ): String {
        require(dockerfilePath.exists() && dockerfilePath.isFile) {
            "File does not exist: $dockerfilePath"
        }

        val nowDate = DateTimeFormatter.ISO_DATE.format(LocalDateTime.now())
        val iidFile = createTempFile("docker-iid", ".txt").toFile()
        try {
            val command = buildList {
                add("docker")
                add("build")

                @Suppress("SpellCheckingInspection")
                add("--iidfile")
                add(iidFile.absolutePath)

                for ((k, v) in buildArgs + ("CACHE_BUST" to nowDate)) {
                    add("--build-arg")
                    add("$k=$v")
                }

                add(".")
            }

            ProcessRunRequest.builder()
                    .command(command)
                    .description("Build Docker image $dockerfilePath")
                    .workingDir(dockerfilePath.parentFile)
                    .timeoutSeconds(timeoutSeconds)
                    .runProcess(processRunner)
                    .assertExitCode(0) { "Failed to build Docker image.\n$stderr" }

            val imageId = iidFile.readText().trim()
            require(imageId.startsWith("sha256:")) { "Unexpected image ID format from --iidfile: $imageId" }
            println("[$logPrefix] Docker image built $imageId")
            return imageId.removePrefix("sha256:").trim()
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
        val maxRetries = 5
        val retryDelayMs = 2_000L
        var lastError: Exception? = null

        for (attempt in 1..maxRetries) {
            try {
                val result = ProcessRunRequest.builder()
                    .command("docker", "port", containerId, "$containerPort/tcp")
                    .description("Query host port for $containerPort")
                    .workingDir(workDir)
                    .timeoutSeconds(5)
                    .quietly()
                    .runProcess(processRunner)
                    .assertExitCode(0) { "Failed to query mapped port for $containerPort: $stderr" }

                // Parse "0.0.0.0:52134" or "[::]:52134" — take the last colon-separated part
                return result.stdout.trim().lines().firstOrNull()?.substringAfterLast(':')?.toIntOrNull()
                    ?: error("Failed to parse host port from: ${result.stdout}")
            } catch (e: Exception) {
                lastError = e
                if (attempt < maxRetries) {
                    println("[DOCKER] Waiting for port $containerPort to be mapped (attempt $attempt/$maxRetries)...")
                    Thread.sleep(retryDelayMs)
                }
            }
        }

        error(
            "Failed to query mapped port for container $containerId port $containerPort after $maxRetries attempts. " +
                    "Last error: $lastError"
        )
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
            .quietly()
            .runProcess(processRunner)

        ProcessRunRequest.builder()
            .command("docker", "rm", "-f", containerId)
            .description("Remove container")
            .workingDir(workingDir = workDir)
            .timeoutSeconds(timeoutSeconds = 5)
            .quietly()
            .runProcess(processRunner)

        println("[$logPrefix] Container removed successfully")
    }

    fun runInContainer(
        request: DockerProcessRunRequest
    ): ProcessResult {
        val shellCommand = escapeShellArgs(request.command)

        val command = buildList {
            add("docker")
            add("exec")
            if (request.detach) add("--detach")
            (environmentVariables + request.extraEnvVars).forEach { (key, value) ->
                add("-e")
                add("$key=$value")
            }
            request.workingDirInContainer?.let {
                add("-w")
                add(it)
            }
            add(request.containerId)
            add("bash")
            add("-c")
            add(shellCommand)
        }

        return ProcessRunRequest.builder()
            .command(command)
            .description(request.description)
            .workingDir(workDir)
            .timeoutSeconds(request.timeoutSeconds)
            .quietly(request.quietly)
            .runProcess(processRunner)
    }

}
