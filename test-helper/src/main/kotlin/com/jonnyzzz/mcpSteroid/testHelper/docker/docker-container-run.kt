/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.testHelper.docker

import com.jonnyzzz.mcpSteroid.testHelper.escapeShellArgs
import com.jonnyzzz.mcpSteroid.testHelper.process.assertExitCode
import java.time.Duration
import java.time.LocalDateTime.now
import java.time.format.DateTimeFormatter

//TODO: use builder args
fun ContainerDriver.runInContainerDetached(
    args: List<String>,
    workingDir: String? = null,
    extraEnvVars: Map<String, String> = emptyMap(),
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

    startProcessInContainer(req)
        .assertExitCode(0) { "Failed to start detached process '$name': $stderr" }

    println("Detached process '$name' started, stdout/stderr at $logDir")
    val info = DetachedContainerProcess(name = name, logDir = logDir)
    return RunningContainerProcess(this, info.name, info.logDir)
}
