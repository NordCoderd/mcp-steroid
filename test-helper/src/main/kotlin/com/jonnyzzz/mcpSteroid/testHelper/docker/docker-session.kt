/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.testHelper.docker

import com.jonnyzzz.mcpSteroid.testHelper.CloseableStack
import com.jonnyzzz.mcpSteroid.testHelper.createTempDirectory
import com.jonnyzzz.mcpSteroid.testHelper.escapeShellArgs
import com.jonnyzzz.mcpSteroid.testHelper.process.assertExitCode
import java.io.File
import java.time.LocalDateTime.now
import java.time.format.DateTimeFormatter

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

    val imageId = buildDockerImage(
        logPrefix = logPrefix,
        dockerfilePath,
        timeoutSeconds = 600,
    )

    return startContainerDriver(lifetime, scope, imageId)
}

fun startContainerDriver(
    lifetime: CloseableStack,
    scope: DockerDriver,
    imageId: String,
    extraEnvVars: Map<String, String> = emptyMap(),
    volumes: List<ContainerVolume> = listOf(),
    ports: List<ContainerPort> = listOf(),
    autoRemove: Boolean = false,
): ContainerDriver {
    val containerId = scope.startContainer(lifetime, imageId, extraEnvVars, volumes, ports, autoRemove = autoRemove)

    // Register with reaper for cleanup on crash/SIGKILL
    DockerReaper.registerContainer(containerId, scope.workDir)

    return ContainerDriverImpl(scope, containerId, imageId, volumes)
}

private class ContainerDriverImpl(
    private val scope: DockerDriver,
    override val containerId: String,
    private val imageName: String,
    override val volumes: List<ContainerVolume> = emptyList(),
    private val hostPorts: Map<Int, Int> = emptyMap(),
) : ContainerDriver {

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
    ) = DockerProcessRunRequest.builder()
            .containerId(containerId)
            .command(args)
            .description("In container: ${args.joinToString(" ")}")
            .timeoutSeconds(timeoutSeconds)
            .quietly(quietly)
            .workingDirInContainer(workingDir)
            .extraEnv(extraEnvVars)
            .detach(false)
            .build()
            .runInContainer(scope)

    override fun runInContainerDetached(
        args: List<String>,
        workingDir: String?,
        extraEnvVars: Map<String, String>
    ): RunningContainerProcess {
        val timestamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS").format(now())
        val name = args.first().substringAfterLast("/")
        val logDir = "/tmp/run-$timestamp-$name"
        // Build the wrapper script that runs the real command,
        // captures its PID, and redirects output to files
        val innerCommand = escapeShellArgs(args)
        val wrapperScript = buildString {
            this.appendLine("#!/bin/bash")
            this.appendLine("$innerCommand >$logDir/stdout.log 2>$logDir/stderr.log &")
            this.appendLine("_PID=\$!")
            this.appendLine("echo \$_PID > $logDir/pid")
            this.appendLine("wait \$_PID")
            this.appendLine("echo \$? > $logDir/exitcode")
        }
        // Write the wrapper script into the container
        val scriptPath = "$logDir/run.sh"
        writeFileInContainer(scriptPath, wrapperScript, executable = true)
        // Run the wrapper script detached

        DockerProcessRunRequest.builder()
            .containerId(containerId)
            .command("bash", scriptPath)
            .description("In detached $innerCommand")
            .timeoutSeconds(10)
            .quietly()
            .workingDirInContainer(workingDir)
            .extraEnv(extraEnvVars)
            .detach(true)
            .runInContainer(scope)
            .assertExitCode(0) { "Failed to start detached process '$name': $stderr" }

        println("[${scope.logPrefix}] Detached process '$name' started, stdout/stderr at $logDir")
        val info = DetachedContainerProcess(name = name, logDir = logDir)
        return RunningContainerProcess(this, info.name, info.logDir)
    }

    override fun writeFileInContainer(
        containerPath: String,
        content: String,
        executable: Boolean
    ) {
        // Ensure parent directory exists
        val parentDir = containerPath.substringBeforeLast('/')

        if (parentDir.isNotEmpty()) {
            DockerProcessRunRequest.builder()
                .containerId(containerId)
                .command("mkdir", "-p", parentDir)
                .description("mkdir $parentDir")
                .timeoutSeconds(5)
                .quietly()
                .runInContainer(scope)
            .assertExitCode(0)
        }

        DockerProcessRunRequest.builder()
            .containerId(containerId)
            .command("bash", "-c", "cat > $containerPath << 'FILE_EOF'\n$content\nFILE_EOF")
            .description("Write content to $containerPath: $content")
            .timeoutSeconds(5)
            .quietly()
            .runInContainer(scope)
            .assertExitCode(0)

        if (executable) {
            DockerProcessRunRequest.builder()
                .containerId(containerId)
                .command(listOf("chmod", "+x", containerPath))
                .description("chmod +x $containerPath")
                .timeoutSeconds(5)
                .quietly()
                .runInContainer(scope)
                .assertExitCode(0)
        }
    }

    override fun toString(): String {
        return "DockerContained(id=$containerId, image=$imageName)"
    }
}