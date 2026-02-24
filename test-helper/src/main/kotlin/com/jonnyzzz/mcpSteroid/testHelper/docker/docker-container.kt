/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.testHelper.docker

import com.jonnyzzz.mcpSteroid.testHelper.process.RunProcessRequest
import com.jonnyzzz.mcpSteroid.testHelper.process.startProcess

data class ContainerHolder(
    val logPrefix: String,
    val startRequest: StartContainerRequest,
    val containerId: String,
) {

    fun newRunProcess() = RunProcessRequest()
        .logPrefix(logPrefix)
}

/**
 * Base class for managing CLI sessions running inside Docker containers.
 * Provides common functionality for building images, starting/stopping containers,
 * and running commands.
 */
interface ContainerDriver : ContainerProcessRunner {
    val containerId: String
    val volumes: List<ContainerVolume>

    fun withEnv(key: String, value: String): ContainerDriver

    companion object
}

fun ContainerDriver.killContainer() {
    RunProcessRequest()
        .logPrefix(containerId)
        .command("docker", "kill", containerId)
        .description("kill container")
        .timeoutSeconds(10)
        .startProcess()
        .awaitForProcessFinish()

    RunProcessRequest()
        .logPrefix(containerId)
        .command("docker", "rm", "-f", containerId)
        .description("Remove container")
        .timeoutSeconds(timeoutSeconds = 5)
        .startProcess()
        .awaitForProcessFinish()

    println("[$containerId] Container removed")
}
