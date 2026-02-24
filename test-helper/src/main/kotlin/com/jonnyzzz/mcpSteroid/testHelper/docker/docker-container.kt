/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.testHelper.docker

import com.jonnyzzz.mcpSteroid.testHelper.process.RunProcessRequest
import com.jonnyzzz.mcpSteroid.testHelper.process.assertExitCode
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

    override fun withSecretPattern(secretPattern: String): ContainerDriver
    fun withEnv(key: String, value: String): ContainerDriver

    fun runInContainerDetached(
        args: List<String>,
        workingDir: String? = null,
        extraEnvVars: Map<String, String> = emptyMap(),
    ) : RunningContainerProcess

    fun writeFileInContainer(
        containerPath: String,
        content: String,
        executable: Boolean = false,
    ) {
        val parentDir = containerPath.substringBeforeLast('/')
        if (parentDir.isNotEmpty()) {
            ContainerProcessRunRequest.builder()
                .command("mkdir", "-p", parentDir)
                .description("mkdir $parentDir")
                .timeoutSeconds(5)
                .quietly()
                .runInContainer(this)
                .assertExitCode(0)
        }
        ContainerProcessRunRequest.builder()
            .command("bash", "-c", "cat > $containerPath << 'FILE_EOF'\n$content\nFILE_EOF")
            .description("Write content to $containerPath")
            .timeoutSeconds(5)
            .quietly()
            .runInContainer(this)
            .assertExitCode(0)
        if (executable) {
            ContainerProcessRunRequest.builder()
                .command("chmod", "+x", containerPath)
                .description("chmod +x $containerPath")
                .timeoutSeconds(5)
                .quietly()
                .runInContainer(this)
                .assertExitCode(0)
        }
    }

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
