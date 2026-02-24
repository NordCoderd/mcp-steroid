/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.testHelper.docker

import com.jonnyzzz.mcpSteroid.testHelper.CloseableStack
import com.jonnyzzz.mcpSteroid.testHelper.escapeShellArgs
import com.jonnyzzz.mcpSteroid.testHelper.process.assertExitCode
import java.time.LocalDateTime.now
import java.time.format.DateTimeFormatter

fun startContainerDriver(
    lifetime: CloseableStack,
    scope: DockerDriver,
    request: StartContainerRequest,
): ContainerDriver {
    val containerId = startDockerContainer(request)

    val driver = ContainerDriverImpl(
        scope,
        containerId,
        request.image ?: error("Missing image for $request"),
        request.volumes
    )

    // Register normal cleanup action
    lifetime.registerCleanupAction {
        driver.killContainer()
    }

    // Register with reaper for cleanup on crash/SIGKILL
    DockerReaper.registerContainer(containerId, scope.workDir)

    return driver
}

private class ContainerDriverImpl(
    private val scope: DockerDriver,
    override val containerId: String,
    private val imageName: String,
    override val volumes: List<ContainerVolume> = emptyList(),
) : ContainerDriver {

    override fun withSecretPattern(secretPattern: String): ContainerDriver {
        return ContainerDriverImpl(scope.withSecretPattern(secretPattern), containerId, imageName, volumes)
    }

    override fun withEnv(key: String, value: String): ContainerDriver {
        return ContainerDriverImpl(scope.withEnv(key, value), containerId, imageName, volumes)
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
            this.appendLine($$"_PID=$!")
            this.appendLine($$"echo $_PID > $$logDir/pid")
            this.appendLine($$"wait $_PID")
            this.appendLine($$"echo $? > $$logDir/exitcode")
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
            .build()
            .runInContainer(scope)
            .assertExitCode(0) { "Failed to start detached process '$name': $stderr" }

        println("[${scope.logPrefix}] Detached process '$name' started, stdout/stderr at $logDir")
        val info = DetachedContainerProcess(name = name, logDir = logDir)
        return RunningContainerProcess(this, info.name, info.logDir)
    }


    override fun toString(): String {
        return "DockerContained(id=$containerId, image=$imageName)"
    }
}