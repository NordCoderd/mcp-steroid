/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.testHelper.docker

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
    )

    companion object
}

