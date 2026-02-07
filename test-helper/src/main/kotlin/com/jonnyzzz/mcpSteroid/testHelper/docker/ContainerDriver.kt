/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.testHelper.docker

import com.jonnyzzz.mcpSteroid.testHelper.CloseableStack
import com.jonnyzzz.mcpSteroid.testHelper.ProcessResult
import com.jonnyzzz.mcpSteroid.testHelper.createTempDirectory
import java.io.File

/**
 * Base class for managing CLI sessions running inside Docker containers.
 * Provides common functionality for building images, starting/stopping containers,
 * and running commands.
 */
interface ContainerDriver {
    /** Maps a [ContainerPort] to the host port it was mapped to. Throws if not mapped. */
    fun mapContainerPortToHostPort(port: ContainerPort): Int

    fun withSecretPattern(secretPattern: String): ContainerDriver
    fun withEnv(key: String, value: String): ContainerDriver

    fun runInContainer(
        args: List<String>,
        workingDir: String? = null,
        timeoutSeconds: Long = 30,
        extraEnvVars: Map<String, String> = emptyMap()
    ): ProcessResult

    fun mkdirs(guestPath: String) = runInContainer(listOf("mkdir", "-p", guestPath))

    fun runInContainerDetached(
        args: List<String>,
        workingDir: String? = null,
        extraEnvVars: Map<String, String> = emptyMap(),
    ) : RunningContainerProcess

    fun writeFileInContainer(
        containerPath: String,
        content: String,
        executable: Boolean = false,
    )

    fun copyFromContainer(
        containerPath: String,
        localPath: File,
    )

    fun copyToContainer(
        localPath: File,
        containerPath: String,
    )

    fun mapGuestPathToHostPath(path: String) : File

    companion object
}

fun ContainerDriver.Companion.startDockerSession(
    lifetime: CloseableStack,
    dockerFileBase: String, //aka codex-cli
    secretPatterns: List<String> = listOf(),
): ContainerDriver {
    val dockerfilePath = File("src/test/docker/$dockerFileBase/Dockerfile")
    require(dockerfilePath.isFile) { "Docker file $dockerfilePath must exist" }

    val logPrefix = dockerFileBase.uppercase().replace("/", "-")
    val workDir = createTempDirectory(logPrefix.lowercase())
    println("[$logPrefix] Creating new session in temp dir: $workDir")
    lifetime.registerCleanupAction {
        workDir.deleteRecursively()
        println("[$logPrefix] Temp directory cleaned up: $workDir")
    }

    val scope = DockerDriver(workDir, logPrefix, secretPatterns)
    val imageName = "$dockerFileBase-test"

    scope.buildDockerImage(
        imageName = imageName,
        dockerfilePath,
        timeoutSeconds = 600
    )

    return startContainerDriver(lifetime, scope, imageName)
}

fun startContainerDriver(
    lifetime: CloseableStack,
    scope: DockerDriver,
    imageName: String,
    extraEnvVars: Map<String, String> = emptyMap(),
    volumes: List<ContainerVolume> = listOf(),
    ports: List<ContainerPort> = listOf(),
): ContainerDriver {
    val containerId = scope.startContainer(lifetime, imageName, extraEnvVars, volumes, ports)

    val hostPorts = ports.associate { p ->
        p.containerPort to scope.queryMappedPort(containerId, p.containerPort)
    }
    if (hostPorts.isNotEmpty()) {
        println("[${scope.logPrefix}] Port mappings: ${hostPorts.entries.joinToString { "${it.key} -> ${it.value}" }}")
    }

    return ContainerDriverImpl(scope, containerId, imageName, volumes, hostPorts)
}

data class ContainerVolume(
    val host: File,
    val guest: String,
    val mode: String = "rw",
)

data class ContainerPort(
    val containerPort: Int,
)

private class ContainerDriverImpl(
    private val scope: DockerDriver,
    private val containerId: String,
    private val imageName: String,
    private val volumes: List<ContainerVolume> = emptyList(),
    private val hostPorts: Map<Int, Int> = emptyMap(),
) : ContainerDriver {
    override fun mapContainerPortToHostPort(port: ContainerPort): Int =
        hostPorts[port.containerPort]
            ?: error("Port ${port.containerPort} is not mapped. Available: ${hostPorts.keys}")

    override fun withSecretPattern(secretPattern: String): ContainerDriver {
        return ContainerDriverImpl(scope.withSecretPattern(secretPattern), containerId, imageName, volumes.toList(), hostPorts)
    }

    override fun withEnv(key: String, value: String): ContainerDriver {
        return ContainerDriverImpl(scope.withEnv(key, value), containerId, imageName, volumes.toList(), hostPorts)
    }

    override fun runInContainer(
        args: List<String>,
        workingDir: String?,
        timeoutSeconds: Long,
        extraEnvVars: Map<String, String>
    ): ProcessResult {
        return scope.runInContainer(containerId, args.toList(), workingDir, timeoutSeconds, extraEnvVars)
    }

    override fun runInContainerDetached(
        args: List<String>,
        workingDir: String?,
        extraEnvVars: Map<String, String>
    ): RunningContainerProcess {
        return scope.runInContainerDetached(containerId, args, workingDir, extraEnvVars)
    }

    override fun writeFileInContainer(
        containerPath: String,
        content: String,
        executable: Boolean
    ) {
        scope.writeFileInContainer(containerId, containerPath, content, executable)
    }

    override fun copyFromContainer(containerPath: String, localPath: File) {
        scope.copyFromContainer(containerId, containerPath, localPath)
    }

    override fun copyToContainer(localPath: File, containerPath: String) {
        scope.copyToContainer(containerId, localPath, containerPath)
    }

    override fun mapGuestPathToHostPath(path: String): File {
        for (v in volumes) {
            if (v.guest == path) {
                return v.host
            }

            if (path.startsWith(v.guest + "/")) {
                val prefix = path.removePrefix(v.guest + "/").trim('/')
                return v.host.resolve(prefix)
            }
        }
        error("Not found volume for guest path $path")
    }

    override fun toString(): String {
        return "DockerContained(id=$containerId, image=$imageName)"
    }
}
