/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.testHelper.docker

/**
 * Represents a process running inside a Docker container with output redirected to files.
 * Stdout, stderr, and PID are stored in [logDir] inside the container.
 * Use [readOutput], [readStderr] to fetch content via `docker exec`.
 * Use [kill] to terminate the process if it is still running.
 */
class RunningContainerProcess(
    private val driver: DockerDriver,
    private val containerId: String,
    /** Name/label for this background process */
    val name: String,
    /** Container-side directory holding stdout.log, stderr.log, and pid */
    val logDir: String,
) {
    val stdoutPath: String get() = "$logDir/stdout.log"
    val stderrPath: String get() = "$logDir/stderr.log"
    val pidPath: String get() = "$logDir/pid"

    /** Read current stdout content from the container. */
    fun readOutput(timeoutSeconds: Long = 10): String {
        val result = driver.runInContainer(
            containerId,
            listOf("cat", stdoutPath),
            timeoutSeconds = timeoutSeconds,
        )
        return result.output
    }

    /** Read current stderr content from the container. */
    fun readStderr(timeoutSeconds: Long = 10): String {
        val result = driver.runInContainer(
            containerId,
            listOf("cat", stderrPath),
            timeoutSeconds = timeoutSeconds,
        )
        return result.output
    }

    /** Read the PID of the background process. Returns null if not available. */
    fun readPid(): String? {
        val result = driver.runInContainer(
            containerId,
            listOf("cat", pidPath),
            timeoutSeconds = 5L,
        )
        return result.output.trim().takeIf { it.isNotEmpty() && result.exitCode == 0 }
    }

    /** Kill the background process if it is still running. */
    fun kill(signal: String = "TERM", timeoutSeconds: Long = 5) {
        val pid = readPid() ?: return
        driver.runInContainer(
            containerId,
            listOf("kill", "-$signal", pid),
            timeoutSeconds = timeoutSeconds,
        )
    }

    /** Check if the process is still running. */
    fun isRunning(timeoutSeconds: Long = 5): Boolean {
        val pid = readPid() ?: return false
        val result = driver.runInContainer(
            containerId,
            listOf("kill", "-0", pid),
            timeoutSeconds = timeoutSeconds,
        )
        return result.exitCode == 0
    }

    override fun toString(): String = "RunningContainerProcess(name=$name, logDir=$logDir)"
}