/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.integration.infra

import com.jonnyzzz.mcpSteroid.testHelper.docker.ContainerDriver
import com.jonnyzzz.mcpSteroid.testHelper.docker.ContainerPort
import java.io.File

abstract class ContainerDriverDelegate<D: ContainerDriverDelegate<D>>(
    protected val delegate: ContainerDriver,
) : ContainerDriver {

    protected abstract fun createNewDriver(delegate: ContainerDriver) : D

    final override val containerId: String by delegate::containerId
    final override fun mapGuestPortToHostPort(port: ContainerPort): Int = delegate.mapGuestPortToHostPort(port)
    final override fun withGuestWorkDir(guestWorkDir: String): ContainerDriver = createNewDriver(delegate.withGuestWorkDir(guestWorkDir))
    final override fun withSecretPattern(secretPattern: String): ContainerDriver = createNewDriver(delegate.withSecretPattern(secretPattern))
    final override fun withEnv(key: String, value: String): ContainerDriver = createNewDriver(delegate.withEnv(key, value))
    final override fun writeFileInContainer(containerPath: String, content: String, executable: Boolean) = delegate.writeFileInContainer(containerPath, content, executable)
    final override fun copyFromContainer(containerPath: String, localPath: File) = delegate.copyFromContainer(containerPath, localPath)
    final override fun copyToContainer(localPath: File, containerPath: String) = delegate.copyToContainer(localPath, containerPath)
    final override fun mapGuestPathToHostPath(path: String): File = delegate.mapGuestPathToHostPath(path)
    override fun toString(): String = "${javaClass.simpleName}($delegate)"
}
