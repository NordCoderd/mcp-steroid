/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.testHelper.docker

import com.jonnyzzz.mcpSteroid.testHelper.CloseableStack

fun startDockerContainerAndDispose(
    lifetime: CloseableStack,
    request: StartContainerRequest,
): ContainerDriver {
    val container = startDockerContainerAndForget(request)

    // Register normal cleanup action
    lifetime.registerCleanupAction {
        container.killContainer()
    }

    // Register with reaper for cleanup on crash/SIGKILL
    DockerReaper.registerContainer(container)

    return container
}
