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
    fun withSecretPattern(secretPattern: String): ContainerDriver
    fun withEnv(key: String, value: String): ContainerDriver

    fun runInContainer(
        vararg args: String,
        timeoutSeconds: Long = 30,
        extraEnvVars: Map<String, String> = emptyMap()
    ): ProcessResult

    fun runInContainerDetached(
        args: List<String>,
        name: String,
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
    volumes: Map<File, String> = emptyMap(),
): ContainerDriver {
    val containerId = scope.startContainer(lifetime, imageName, extraEnvVars, volumes)
    return ContainerDriverImpl(scope, containerId, imageName)
}

private class ContainerDriverImpl(
    private val scope: DockerDriver,
    private val containerId: String,
    private val imageName: String
) : ContainerDriver {
    override fun withSecretPattern(secretPattern: String): ContainerDriver {
        return ContainerDriverImpl(scope.withSecretPattern(secretPattern), containerId, imageName)
    }

    override fun withEnv(key: String, value: String): ContainerDriver {
        return ContainerDriverImpl(scope.withEnv(key, value), containerId, imageName)
    }

    override fun runInContainer(
        vararg args: String,
        timeoutSeconds: Long,
        extraEnvVars: Map<String, String>
    ): ProcessResult {
        return scope.runInContainer(containerId, args.toList(), timeoutSeconds, extraEnvVars)
    }

    override fun runInContainerDetached(
        args: List<String>,
        name: String,
        extraEnvVars: Map<String, String>
    ): RunningContainerProcess {
        return scope.runInContainerDetached(containerId, args, name, extraEnvVars)
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

    override fun toString(): String {
        return "DockerContained(id=$containerId, image=$imageName)"
    }
}
