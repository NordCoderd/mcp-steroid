/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.testHelper.docker

import com.jonnyzzz.mcpSteroid.testHelper.CloseableStack
import com.jonnyzzz.mcpSteroid.testHelper.escapeShellArgs
import com.jonnyzzz.mcpSteroid.testHelper.process.RunProcessRequest
import com.jonnyzzz.mcpSteroid.testHelper.process.StartedProcess
import com.jonnyzzz.mcpSteroid.testHelper.process.startProcess
import kotlin.collections.component1
import kotlin.collections.component2
import kotlin.collections.forEach
import kotlin.collections.plus

fun startContainerDriver(
    lifetime: CloseableStack,
    scope: DockerDriver,
    request: StartContainerRequest,
): ContainerDriver {
    val containerId = startDockerContainer(request)

    val driver = ContainerDriverImpl(
        logPrefix = request.logPrefix ?: error("No logPrefix provided"),
        environmentVariables = emptyMap(),
        containerId = containerId,
        imageName = request.image ?: error("Missing image for $request"),
        volumes = request.volumes
    )

    // Register normal cleanup action
    lifetime.registerCleanupAction {
        driver.killContainer()
    }

    // Register with reaper for cleanup on crash/SIGKILL
    DockerReaper.registerContainer(containerId, scope.workDir)

    return driver
}

private class ContainerDriverImpl(
    val logPrefix: String,
    val environmentVariables: Map<String, String>,
    override val containerId: String,
    private val imageName: String,
    override val volumes: List<ContainerVolume> = emptyList(),
) : ContainerDriver {

    override fun withEnv(key: String, value: String): ContainerDriver {
        return ContainerDriverImpl(logPrefix, environmentVariables + (key to value), containerId, imageName, volumes)
    }

    override fun toString(): String {
        return "DockerContained(id=$containerId, image=$imageName)"
    }

    override fun startProcessInContainer(
        request: ExecContainerProcessRequest
    ): StartedProcess {

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
            add(containerId)
            add("bash")
            add("-c")
            add(escapeShellArgs(request.args))
        }

        return RunProcessRequest()
            .withLogPrefix(logPrefix)
            .timeoutSeconds(30)
            .command(command)
            .description(request.description ?: error("Missing description in $request"))
            .withTimeout(request.timeout)
            .quietly(request.quietly)
            .startProcess()
    }
}