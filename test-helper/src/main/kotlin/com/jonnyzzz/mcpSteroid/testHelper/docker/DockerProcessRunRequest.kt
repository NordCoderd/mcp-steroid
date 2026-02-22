/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.testHelper.docker

open class DockerProcessRunRequest(
    parent: ContainerProcessRunRequest,
    val containerId: String,
) : ContainerProcessRunRequest(parent) {
    companion object
}

fun DockerProcessRunRequest.Companion.builder() = DockerProcessRunRequestBuilder()

open class DockerProcessRunRequestBuilder<R : DockerProcessRunRequestBuilder<R>>() :
    ContainerProcessRunRequestBuilder<R>() {
    var containerId: String? = null

    open fun containerId(containerId: String) = apply { this.containerId = containerId }

    override fun build(): DockerProcessRunRequest {
        return DockerProcessRunRequest(super.build(), containerId ?: error("Container ID must be set"))
    }
}
