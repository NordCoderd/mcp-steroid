/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.testHelper.docker

import com.jonnyzzz.mcpSteroid.testHelper.process.ProcessResult
import java.io.File

/**
 * Base class for managing CLI sessions running inside Docker containers.
 * Provides common functionality for building images, starting/stopping containers,
 * and running commands.
 */
interface ContainerDriver : ContainerProcessRunner {
    val containerId: String

    fun mapGuestPathToHostPath(path: String) : File
    fun mapGuestPortToHostPort(port: ContainerPort): Int

    fun withGuestWorkDir(guestWorkDir: String): ContainerDriver
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

fun ContainerDriver.mkdirs(guestPath: String): ProcessResult {
    emptyMap<String, String>()
    return ContainerProcessRunRequest
        .builder()
        .command("mkdir", "-p", guestPath)
        .quietly()
        .runInContainer(this)
}
