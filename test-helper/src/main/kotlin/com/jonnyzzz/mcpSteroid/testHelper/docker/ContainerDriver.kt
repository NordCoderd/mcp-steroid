/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.testHelper.docker

import com.jonnyzzz.mcpSteroid.testHelper.CloseableStack
import com.jonnyzzz.mcpSteroid.testHelper.docker.DockerDriver
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

    fun runInContainer(
        vararg args: String,
        timeoutSeconds: Long = 30,
        extraEnvVars: Map<String, String> = emptyMap()
    ): ProcessResult

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

    //TODO: drop image it it's older than one day
    scope.buildDockerImage(
        imageName = imageName,
        dockerfilePath,
        timeoutSeconds = 600
    )

    //we are not disposing the image
    val containerId = scope.startContainer(imageName)
    lifetime.registerCleanupAction {
        println("[$logPrefix] Stopping and removing container: $containerId")
        scope.killContainer(containerId)
    }

    return ContainerDriverImpl(scope, containerId, dockerFileBase)
}

private class ContainerDriverImpl(
    private val scope: DockerDriver,
    private val containerId: String,
    private val dockerFileBase: String
) : ContainerDriver {
    override fun withSecretPattern(secretPattern: String): ContainerDriver {
        return ContainerDriverImpl(scope.withSecretPattern(secretPattern), containerId, dockerFileBase)
    }

    override fun runInContainer(
        vararg args: String,
        timeoutSeconds: Long,
        extraEnvVars: Map<String, String>
    ): ProcessResult {
        return scope.runInContainer(containerId, args.toList(), timeoutSeconds, extraEnvVars)
    }

    override fun toString(): String {
        return "DockerContained(id=$containerId, image=$dockerFileBase)"
    }
}