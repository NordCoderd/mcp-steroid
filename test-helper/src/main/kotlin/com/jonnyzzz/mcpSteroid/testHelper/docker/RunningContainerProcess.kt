/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.testHelper.docker

import com.jonnyzzz.mcpSteroid.testHelper.process.ProcessResult
import com.jonnyzzz.mcpSteroid.testHelper.process.assertExitCode
import kotlin.sequences.forEach

/**
 * Represents a process running inside a Docker container with output redirected to files.
 * Stdout, stderr, and PID are stored in [logDir] inside the container.
 * Use [readStdOut], [readStderr] to fetch content via `docker exec`.
 * Use [kill] to terminate the process if it is still running.
 */
class RunningContainerProcess(
    private val driver: ContainerDriver,
    /** Name/label for this background process */
    val name: String,
    /** Container-side directory holding stdout.log, stderr.log, and pid */
    val logDir: String,
) : ProcessResult {
    val stdoutPath: String get() = "$logDir/stdout.log"
    val stderrPath: String get() = "$logDir/stderr.log"
    private val pidPath: String get() = "$logDir/pid"
    private val exitCodePath: String get() = "$logDir/exitcode"

    override val exitCode: Int?
        get() = readExitCode()

    override val stdout: String
        get() = readStdOut()

    override val stderr: String
        get() = readStderr()

    fun printProcessInfo() {
        val exitCode = readExitCode()
        val isRunning = isRunning()
        val output = buildString {
            appendLine()
            if (isRunning) {
                appendLine("[$name] Process is till running...")
            } else {
                appendLine("[$name] Process is exited with code $exitCode!")
            }
            readStdOut().lineSequence().forEach { line -> appendLine("[$name OUT] $line") }
            appendLine()
            readStderr().lineSequence().forEach { line -> appendLine("[$name ERR] $line") }
            appendLine()
        }
        println(output)
    }

    /** Read current stdout content from the container. */
    fun readStdOut(timeoutSeconds: Long = 10): String {
        val result = driver.runInContainer(
            listOf("cat", stdoutPath),
            timeoutSeconds = timeoutSeconds,
        )
        return result.stdout
    }

    /** Read current stderr content from the container. */
    fun readStderr(timeoutSeconds: Long = 10): String {
        val result = driver.runInContainer(
            listOf("cat", stderrPath),
            timeoutSeconds = timeoutSeconds,
        )
        return result.stdout
    }

    val pid: Long by lazy {
        val result = driver.runInContainer(
            listOf("cat", pidPath),
            timeoutSeconds = 5L,
        ).assertExitCode(0)
        result.stdout.trim().toLong()
    }

    /**
     * Read the exit code of the process. Returns null if the process
     * is still running (exitcode file not yet written).
     */
    fun readExitCode(timeoutSeconds: Long = 5): Int? {
        val result = driver.runInContainer(
            listOf("cat", exitCodePath),
            timeoutSeconds = timeoutSeconds,
        )
        if (result.exitCode != 0) return null
        return result.stdout.trim().toIntOrNull()
    }

    /** Kill the background process if it is still running. */
    fun kill(signal: String = "TERM", timeoutSeconds: Long = 5) {
        driver.runInContainer(
            listOf("kill", "-$signal", this.pid.toString()),
            timeoutSeconds = timeoutSeconds,
        )
    }

    /** Check if the process is still running. */
    fun isRunning(timeoutSeconds: Long = 5): Boolean {
        val result = driver.runInContainer(
            listOf("kill", "-0", pid.toString()),
            timeoutSeconds = timeoutSeconds,
        )
        return result.exitCode == 0
    }

    override fun toString(): String = "RunningContainerProcess(name=$name, logDir=$logDir)"
}
