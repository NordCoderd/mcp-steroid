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



@Suppress("DATA_CLASS_COPY_VISIBILITY_WILL_BE_CHANGED_WARNING", "DataClassPrivateConstructor")
data class StartContainerRequest private constructor(
    val imageName: String? = null,
    val extraEnvVars: Map<String, String> = emptyMap(),
    val volumes: List<ContainerVolume> = emptyList(),
    val ports: List<ContainerPort> = emptyList(),
    val entryPoint: List<String> = emptyList(),
    val autoRemove: Boolean = true,
    val timeout: Duration = Duration.ofMinutes(5),
) {
    companion object {
        operator fun invoke() : StartContainerRequest = StartContainerRequest()
    }

    fun imageName(x : String) = copy(imageName = x)
    fun extraEnvVars(x : Map<String, String>) = copy(extraEnvVars = x)
    fun volumes(x : List<ContainerVolume>) = copy(volumes = x)
    fun ports(x : List<ContainerPort>) = copy(ports = x)
    fun entryPoint(x: List<String>) = copy(entryPoint = x)
    fun autoRemove(x : Boolean) = copy(autoRemove = x)
    fun timeout(x : Duration) = copy(timeout = x)
}

