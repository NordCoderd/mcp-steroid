/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.testHelper.docker

import com.jonnyzzz.mcpSteroid.testHelper.process.RunProcessRequest
import com.jonnyzzz.mcpSteroid.testHelper.process.assertExitCode
import com.jonnyzzz.mcpSteroid.testHelper.process.startProcess
import java.time.Duration
import kotlin.collections.component1
import kotlin.collections.component2
import kotlin.collections.forEach


@Suppress("DATA_CLASS_COPY_VISIBILITY_WILL_BE_CHANGED_WARNING", "DataClassPrivateConstructor")
data class StartContainerRequest private constructor(
    val image: String? = null,
    val logPrefix: String? = null,
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

    fun logPrefix(logPrefix: String) = copy(logPrefix = logPrefix)
    fun image(image : String) = copy(image = image)
    fun extraEnvVars(extraEnvVars : Map<String, String>) = copy(extraEnvVars = extraEnvVars)
    fun volumes(volumes : List<ContainerVolume>) = copy(volumes = volumes)
    fun volumes(vararg volumes : ContainerVolume) = volumes(volumes.asList())
    fun ports(ports : List<ContainerPort>) = copy(ports = ports)
    fun ports(vararg ports : ContainerPort) = ports(ports.asList())
    fun entryPoint(args: List<String>) = copy(entryPoint = args)
    fun entryPoint(vararg args: String) = entryPoint(args.toList())
    fun autoRemove(autoRemove : Boolean) = copy(autoRemove = autoRemove)
    fun timeout(timeout : Duration) = copy(timeout = timeout)
}


fun startDockerContainer(
    request: StartContainerRequest,
): String {
    val imageId = request.image ?: error("No image name")

    val command = buildList {
        add("docker")
        add("run")
        add("-d")
        if (request.autoRemove) add("--rm")
        add("--add-host=host.docker.internal:host-gateway")

        request.extraEnvVars.forEach { (key, value) ->
            add("-e")
            add("$key=$value")
        }

        request.volumes.forEach { v ->
            add("-v")
            add("${v.host.absolutePath}:${v.guest}:${v.mode}")
        }

        request.ports.forEach { p ->
            add("-p")
            add("0:${p.containerPort}")
        }

        add(imageId)

        // Add container command if specified
        addAll(request.entryPoint)
    }

    val result = RunProcessRequest()
        .command(command)
        .logPrefix(request.logPrefix ?: imageId)
        .description("Start container from $imageId with ${request.entryPoint}")
        .withTimeout(request.timeout)
        .startProcess()
        .assertExitCode(0) {
            "Failed to start Docker container: $stderr"
        }

    val containerId = result.stdout.trim()
    if (containerId.isEmpty()) {
        throw IllegalStateException("Failed to start Docker container: ${result.stderr}")
    }

    println("[${request.logPrefix}] Container started: $containerId")
    return containerId
}
