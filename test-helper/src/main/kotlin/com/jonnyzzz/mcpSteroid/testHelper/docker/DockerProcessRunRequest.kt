/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.testHelper.docker

import java.time.Duration

open class DockerProcessRunRequest(
    parent: ContainerProcessRunRequest,
    val containerId: String,
) : ContainerProcessRunRequest(parent) {
    companion object
}

fun DockerProcessRunRequest.Companion.builder() = DockerProcessRunRequestBuilder()


fun DockerProcessRunRequest.runInContainer(container: DockerDriver) = container.runInContainer(this)
fun <R : DockerProcessRunRequestBuilder<R>> DockerProcessRunRequestBuilder<R>.runInContainer(container: DockerDriver) =
    build().runInContainer(container)

open class DockerProcessRunRequestBuilder<R : DockerProcessRunRequestBuilder<R>>() :
    ContainerProcessRunRequestBuilder<R>() {
    var containerId: String? = null

    open fun containerId(containerId: String) = apply { this.containerId = containerId }

    override fun build(): DockerProcessRunRequest {
        return DockerProcessRunRequest(super.build(), containerId ?: error("Container ID must be set"))
    }
}




