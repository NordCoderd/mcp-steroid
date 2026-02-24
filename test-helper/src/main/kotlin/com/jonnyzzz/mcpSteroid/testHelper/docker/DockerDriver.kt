/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.testHelper.docker

import com.jonnyzzz.mcpSteroid.testHelper.CloseableStack
import com.jonnyzzz.mcpSteroid.testHelper.escapeShellArgs
import com.jonnyzzz.mcpSteroid.testHelper.process.*
import java.io.File
import java.time.Duration
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.io.path.createTempFile

class DockerDriver(
    val workDir: File,
    val logPrefix: String,
    val secretPatterns: List<String> = emptyList(),
    val environmentVariables: Map<String, String> = emptyMap(),
) {
    fun withSecretPattern(secretPattern: String): DockerDriver {
        return DockerDriver(
            workDir,
            logPrefix,
            (secretPatterns + secretPattern).distinct(),
            environmentVariables,
        )
    }

    fun withEnv(key: String, value: String): DockerDriver {
        return DockerDriver(
            workDir,
            logPrefix,
            secretPatterns,
            (environmentVariables + (key to value)).toSortedMap(),
        )
    }

    //TODO: rework it
    val processRunner get() = ProcessRunner(logPrefix, secretPatterns.toList())

    val runProcessTemplate get() = RunProcessRequest()
        .withLogPrefix(logPrefix)
        .withSecretPatterns(secretPatterns)
        .workingDir(workDir)
        .timeoutSeconds(30)

    /**
     * Tag an existing Docker image with a new name.
     *
     * @param imageId Source image reference (e.g. `sha256:<hex>` or existing tag)
     * @param tag Target tag (e.g. `mcp-steroid-ide-base-test:latest`)
     */
    fun tagDockerImage(imageId: String, tag: String) {
        runProcessTemplate
            .command("docker", "tag", imageId, tag)
            .description("Tag Docker image as $tag")
            .quietly()
            .startProcess()
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

            runProcessTemplate
                    .command(command)
                    .description("Build Docker image $dockerfilePath")
                    .workingDir(dockerfilePath.parentFile)
                    .timeoutSeconds(timeoutSeconds)
                    .startProcess()
                    .assertExitCode(0) { "Failed to build Docker image.\n$stderr" }

            val imageId = iidFile.readText().trim()
            require(imageId.startsWith("sha256:")) {
                @Suppress("SpellCheckingInspection")
                "Unexpected image ID format from --iidfile: $imageId"
            }

            println("[$logPrefix] Docker image built $imageId")
            return imageId.removePrefix("sha256:").trim()
        } finally {
            iidFile.delete()
        }
    }

    fun startContainer2(
        request: StartContainerRequest,
    ): String {
        val imageName = request.imageName ?: error("No image name")

        val command = buildList {
            add("docker")
            add("run")
            add("-d")
            if (request.autoRemove) add("--rm")
            add("--add-host=host.docker.internal:host-gateway")

            (environmentVariables + request.extraEnvVars).forEach { (key, value) ->
                add("-e")
                add("$key=$value")
            }

            request.volumes.forEach { v ->
                add("-v")
                add("${v.host.absolutePath}:${v.guest}:${v.mode}")
            }

            request.ports.forEach { p ->
                add("-p")
                add("0:${p.containerPort}")
            }

            add(imageName)

            // Add container command if specified
            addAll(request.entryPoint)
        }

        val result = runProcessTemplate
            .command(command)
            .description("Start container from $imageName with ${request.entryPoint}")
            .withTimeout(request.timeout)
            .startProcess()
            .assertExitCode(0) {
                "Failed to start Docker container: $stderr"
            }

        val containerId = result.stdout.trim()
        if (containerId.isEmpty()) {
            throw IllegalStateException("Failed to start Docker container: ${result.stderr}")
        }

        println("[$logPrefix] Container started: $containerId")
        return containerId
    }

    //TODO: this should either return container driver, or move outside of here
    fun startContainer(
        lifetime: CloseableStack,
        imageName: String,
        extraEnvVars: Map<String, String> = emptyMap(),
        volumes: List<ContainerVolume> = emptyList(),
        ports: List<ContainerPort> = emptyList(),
        cmd: List<String> = emptyList(),
        autoRemove: Boolean = true,
        timeoutSeconds: Long = 300,
    ): String {
        val containerId = startContainer2(
            StartContainerRequest()
                .imageName(imageName)
                .extraEnvVars(extraEnvVars)
                .volumes(volumes)
                .ports(ports)
                .entryPoint(cmd)
                .autoRemove(autoRemove)
                .timeout(Duration.ofSeconds(timeoutSeconds))
        )

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
        val result = runProcessTemplate
            .command("docker", "port", containerId, "$containerPort/tcp")
            .description("Query host port for $containerPort")
            .timeoutSeconds(5)
            .startProcess()
            .assertExitCode(0) { "Failed to map container port $containerPort for $containerId" }

        val mappedPort = parseMappedPortOutput(result.stdout)
        if (result.exitCode == 0 && mappedPort != null) {
            return mappedPort
        }

        error("Failed to query mapped port for container $containerId port $containerPort. $result")
    }

    companion object {
        internal fun parseMappedPortOutput(stdout: String): Int? {
            return stdout
                .lineSequence()
                .map { it.trim() }
                .firstNotNullOfOrNull { line ->
                    line.takeIf { it.isNotBlank() }?.substringAfterLast(':')?.toIntOrNull()
                }
        }

        internal fun parseContainerRunningState(stdout: String): Boolean? {
            return when (stdout.trim().lowercase()) {
                "true" -> true
                "false" -> false
                else -> null
            }
        }
    }

    /**
     * Query bridge-network container IP (for example 172.17.x.x).
     * Returns null when inspect output does not contain an address.
     */
    fun queryContainerIp(containerId: String): String? {
        val result = runProcessTemplate
            .command(
                "docker",
                "inspect",
                "-f",
                "{{range .NetworkSettings.Networks}}{{.IPAddress}} {{end}}",
                containerId
            )
            .description("Query container IP")
            .timeoutSeconds(5)
            .quietly()
            .startProcess()
            .assertExitCode(0) { "Failed to query container IP: $stderr" }

        return result.stdout
            .trim()
            .split(Regex("\\s+"))
            .firstOrNull { it.isNotBlank() }
    }

    fun killContainer(containerId: String) {
        runProcessTemplate
            .command("docker", "kill", containerId)
            .description("kill container")
            .timeoutSeconds(10)
            .startProcess()
            .awaitForProcessFinish()

        runProcessTemplate
            .command("docker", "rm", "-f", containerId)
            .description("Remove container")
            .timeoutSeconds(timeoutSeconds = 5)
            .startProcess()
            .awaitForProcessFinish()

        println("[$logPrefix] Container removed successfully")
    }

    //TODO: return StartedProcess!
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

        return runProcessTemplate
            .command(command)
            .description(request.description)
            .timeoutSeconds(request.timeoutSeconds)
            .quietly(request.quietly)
            .startProcess()
    }
}
