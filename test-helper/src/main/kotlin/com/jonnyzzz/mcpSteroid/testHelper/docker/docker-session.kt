/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.testHelper.docker

import com.jonnyzzz.mcpSteroid.testHelper.CloseableStack
import com.jonnyzzz.mcpSteroid.testHelper.createTempDirectory
import com.jonnyzzz.mcpSteroid.testHelper.process.ProcessResult
import java.io.File

fun ContainerDriver.Companion.startDockerSession(
    lifetime: CloseableStack,
    dockerFileBase: String, //aka codex-cli
    secretPatterns: List<String> = listOf(),
): ContainerDriver {
    val dockerfilePath = File("src/test/docker/$dockerFileBase/Dockerfile")
    require(dockerfilePath.isFile) { "Docker file $dockerfilePath must exist" }

    val logPrefix = dockerFileBase.uppercase().replace("/", "-")
    val workDir = createTempDirectory(logPrefix.lowercase())
    println("[$logPrefix] Creating new session in temp dir: $workDir")
    lifetime.registerCleanupAction {
        workDir.deleteRecursively()
        println("[$logPrefix] Temp directory cleaned up: $workDir")
    }

    val scope = DockerDriver(workDir, logPrefix, secretPatterns)
    val imageName = "$dockerFileBase-test"

    val imageId = scope.buildDockerImage(
        imageName = imageName,
        dockerfilePath,
        timeoutSeconds = 600
    )

    return startContainerDriver(lifetime, scope, imageId)
}

fun startContainerDriver(
    lifetime: CloseableStack,
    scope: DockerDriver,
    imageName: String,
    extraEnvVars: Map<String, String> = emptyMap(),
    volumes: List<ContainerVolume> = listOf(),
    ports: List<ContainerPort> = listOf(),
    autoRemove: Boolean = false,
): ContainerDriver {
    val containerId = scope.startContainer(lifetime, imageName, extraEnvVars, volumes, ports, autoRemove = autoRemove)

    // Register with reaper for cleanup on crash/SIGKILL
    DockerReaper.registerContainer(containerId, scope.workDir)

    val hostPorts = ports.associate { p ->
        p.containerPort to scope.queryMappedPort(containerId, p.containerPort)
    }
    if (hostPorts.isNotEmpty()) {
        println("[${scope.logPrefix}] Port mappings: ${hostPorts.entries.joinToString { "${it.key} -> ${it.value}" }}")
    }

    return ContainerDriverImpl(scope, containerId, imageName, volumes, hostPorts)
}

private class ContainerDriverImpl(
    private val scope: DockerDriver,
    override val containerId: String,
    private val imageName: String,
    private val volumes: List<ContainerVolume> = emptyList(),
    private val hostPorts: Map<Int, Int> = emptyMap(),
) : ContainerDriver {
    override fun mapGuestPortToHostPort(port: ContainerPort): Int =
        hostPorts[port.containerPort]
            ?: error("Port ${port.containerPort} is not mapped. Available: ${hostPorts.keys}")

    override fun withGuestWorkDir(guestWorkDir: String): ContainerDriver {
        return ContainerDriverImpl(scope.withGuestWorkDir(guestWorkDir), containerId, imageName, volumes, hostPorts)
    }

    override fun withSecretPattern(secretPattern: String): ContainerDriver {
        return ContainerDriverImpl(scope.withSecretPattern(secretPattern), containerId, imageName, volumes, hostPorts)
    }

    override fun withEnv(key: String, value: String): ContainerDriver {
        return ContainerDriverImpl(scope.withEnv(key, value), containerId, imageName, volumes, hostPorts)
    }

    override fun runInContainer(
        args: List<String>,
        workingDir: String?,
        timeoutSeconds: Long,
        extraEnvVars: Map<String, String>,
        quietly: Boolean,
    ): ProcessResult {
        return scope.runInContainer(containerId, args.toList(), workingDir, timeoutSeconds, extraEnvVars, quietly = quietly)
    }

    override fun runInContainerDetached(
        args: List<String>,
        workingDir: String?,
        extraEnvVars: Map<String, String>
    ): RunningContainerProcess {
        val info = scope.runInContainerDetached(containerId, args, workingDir, extraEnvVars)
        return RunningContainerProcess(this, info.name, info.logDir)
    }

    override fun writeFileInContainer(
        containerPath: String,
        content: String,
        executable: Boolean
    ) {
        scope.writeFileInContainer(containerId, containerPath, content, executable)
    }

    override fun copyFromContainer(containerPath: String, localPath: File) {
        scope.copyFromContainer(containerId, containerPath, localPath)
    }

    override fun copyToContainer(localPath: File, containerPath: String) {
        scope.copyToContainer(containerId, localPath, containerPath)
    }

    override fun mapGuestPathToHostPath(path: String): File {
        for (v in volumes) {
            if (v.guest == path) {
                return v.host
            }

            if (path.startsWith(v.guest + "/")) {
                val prefix = path.removePrefix(v.guest + "/").trim('/')
                return v.host.resolve(prefix)
            }
        }
        error("Not found volume for guest path $path")
    }

    override fun toString(): String {
        return "DockerContained(id=$containerId, image=$imageName)"
    }
}