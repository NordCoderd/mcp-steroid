/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.integration.infra

import com.jonnyzzz.mcpSteroid.testHelper.docker.ContainerDriver
import com.jonnyzzz.mcpSteroid.testHelper.docker.ContainerPort
import com.jonnyzzz.mcpSteroid.testHelper.docker.ContainerVolume
import java.io.File

abstract class ContainerDriverDelegate<D: ContainerDriverDelegate<D>>(
    protected val delegate: ContainerDriver,
) : ContainerDriver {

    protected abstract fun createNewDriver(delegate: ContainerDriver) : D

    final override val containerId: String by delegate::containerId
    final override val volumes: List<ContainerVolume> by delegate::volumes

    final override fun withEnv(key: String, value: String): ContainerDriver = createNewDriver(delegate.withEnv(key, value))
    final override fun writeFileInContainer(containerPath: String, content: String, executable: Boolean) = delegate.writeFileInContainer(containerPath, content, executable)
    override fun toString(): String = "${javaClass.simpleName}($delegate)"
}
