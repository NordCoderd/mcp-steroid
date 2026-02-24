/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.testHelper.docker

import com.jonnyzzz.mcpSteroid.testHelper.process.RunProcessRequest
import com.jonnyzzz.mcpSteroid.testHelper.process.assertExitCode
import com.jonnyzzz.mcpSteroid.testHelper.process.startProcess

data class ContainerPort(
    val containerPort: Int,
)

/**
 * Query the host port mapped to a container port.
 * Docker output format: "0.0.0.0:52134" or "[::]:52134"
 */
fun ContainerDriver.mapGuestPortToHostPort(containerPort: ContainerPort): Int {
    val result = RunProcessRequest()
        .logPrefix(containerId)
        .command("docker", "port", containerId, "${containerPort.containerPort}/tcp")
        .description("Query host port for $containerPort")
        .timeoutSeconds(5)
        .startProcess()
        .assertExitCode(0) { "Failed to map container port $containerPort for $containerId" }

    val mappedPort = parseMappedPortOutput(result.stdout)
    if (result.exitCode == 0 && mappedPort != null) {
        return mappedPort
    }

    error("Failed to query mapped port for container $containerId port $containerPort. $result")
}

internal fun parseMappedPortOutput(stdout: String): Int? {
    return stdout
        .lineSequence()
        .map { it.trim() }
        .firstNotNullOfOrNull { line ->
            line.takeIf { it.isNotBlank() }?.substringAfterLast(':')?.toIntOrNull()
        }
}

