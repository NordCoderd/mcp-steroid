/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.testHelper.docker

import com.jonnyzzz.mcpSteroid.testHelper.CloseableStack
import com.jonnyzzz.mcpSteroid.testHelper.escapeShellArgs
import com.jonnyzzz.mcpSteroid.testHelper.process.ProcessResult
import com.jonnyzzz.mcpSteroid.testHelper.process.RunProcessRequest
import com.jonnyzzz.mcpSteroid.testHelper.process.assertExitCode
import com.jonnyzzz.mcpSteroid.testHelper.process.startProcess
import java.time.Duration
import java.time.LocalDateTime.now
import java.time.format.DateTimeFormatter
import kotlin.collections.component1
import kotlin.collections.component2
import kotlin.collections.forEach
import kotlin.collections.plus

fun startContainerDriver(
    lifetime: CloseableStack,
    scope: DockerDriver,
    request: StartContainerRequest,
): ContainerDriver {
    val containerId = startDockerContainer(request)

    val driver = ContainerDriverImpl(
        logPrefix = request.logPrefix ?: error("No logPrefix provided"),
        environmentVariables = emptyMap(),
        containerId = containerId,
        imageName = request.image ?: error("Missing image for $request"),
        volumes = request.volumes
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
    val logPrefix: String,
    val environmentVariables: Map<String, String>,
    override val containerId: String,
    private val imageName: String,
    override val volumes: List<ContainerVolume> = emptyList(),
) : ContainerDriver {

    override fun withEnv(key: String, value: String): ContainerDriver {
        return ContainerDriverImpl(logPrefix, environmentVariables + (key to value), containerId, imageName, volumes)
    }

    override fun runInContainer(
        args: List<String>,
        workingDir: String?,
        timeoutSeconds: Long,
        extraEnvVars: Map<String, String>,
        quietly: Boolean,
    ): ProcessResult {
        val req = RunContainerProcessRequest()
            .args(args)
            .description("In container: ${args.joinToString(" ")}")
            .timeout(Duration.ofSeconds(timeoutSeconds))
            .quietly(quietly)
            .workingDirInContainer(workingDir)
            .extraEnvVars(extraEnvVars)

        return runInContainerEx(req)
    }

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

        val req = RunContainerProcessRequest()
            .args("bash", scriptPath)
            .description("In detached $innerCommand")
            .timeout(Duration.ofSeconds(10))
            .quietly()
            .workingDirInContainer(workingDir)
            .extraEnvVars(extraEnvVars)
            .detach(true)

        runInContainerEx(req)
            .assertExitCode(0) { "Failed to start detached process '$name': $stderr" }

        println("[${logPrefix}] Detached process '$name' started, stdout/stderr at $logDir")
        val info = DetachedContainerProcess(name = name, logDir = logDir)
        return RunningContainerProcess(this, info.name, info.logDir)
    }

    override fun toString(): String {
        return "DockerContained(id=$containerId, image=$imageName)"
    }

    //TODO: return StartedProcess!
    fun runInContainerEx(
        request: RunContainerProcessRequest
    ): ProcessResult {

        val command = buildList {
            add("docker")
            add("exec")
            if (request.detach) add("--detach")
            (environmentVariables + request.extraEnvVars).forEach { (key, value) ->
                add("-e")
                add("$key=$value")
            }
            request.workingDirInContainer?.let {
                add("-w")
                add(it)
            }
            add(containerId)
            add("bash")
            add("-c")
            add(escapeShellArgs(request.args))
        }

        return RunProcessRequest()
            .withLogPrefix(logPrefix)
            .timeoutSeconds(30)
            .command(command)
            .description(request.description ?: error("Missing description in $request"))
            .withTimeout(request.timeout)
            .quietly(request.quietly)
            .startProcess()
            .awaitForProcessFinish()
    }
}