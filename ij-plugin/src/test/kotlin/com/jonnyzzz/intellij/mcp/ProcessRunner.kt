/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.intellij.mcp

import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Result from running a process.
 */
data class ProcessResult(
    val exitCode: Int,
    val output: String,
    val stderr: String
)

/**
 * Utility for running processes with consistent logging.
 * All output is logged to stdout with prefixes for easy debugging.
 */
object ProcessRunner {
    /**
     * Run a process and log all output with prefixes.
     *
     * @param command The command to run as a list of arguments
     * @param description A short description of what this command does (for logging)
     * @param workingDir Working directory for the process
     * @param timeoutSeconds Maximum time to wait for the process to complete
     * @param logPrefix Prefix for log messages (default: "PROCESS")
     */
    fun run(
        command: List<String>,
        description: String,
        workingDir: File,
        timeoutSeconds: Long = 30,
        logPrefix: String = "PROCESS"
    ): ProcessResult {
        println("[$logPrefix] $description: ${command.joinToString(" ")}")

        val processBuilder = ProcessBuilder(command)
        processBuilder.directory(workingDir)
        processBuilder.redirectInput(ProcessBuilder.Redirect.PIPE)
        processBuilder.redirectOutput(ProcessBuilder.Redirect.PIPE)
        processBuilder.redirectError(ProcessBuilder.Redirect.PIPE)

        val process = processBuilder.start()
        process.outputStream.close()

        val outputBuilder = StringBuilder()
        val errorBuilder = StringBuilder()

        val outputThread = Thread {
            process.inputStream.reader().forEachLine { line ->
                println("[$logPrefix OUT] $line")
                outputBuilder.appendLine(line)
            }
        }
        val errorThread = Thread {
            process.errorStream.reader().forEachLine { line ->
                println("[$logPrefix ERR] $line")
                errorBuilder.appendLine(line)
            }
        }

        outputThread.start()
        errorThread.start()

        val completed = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)

        if (!completed) {
            process.destroyForcibly()
            println("[$logPrefix] Timed out after ${timeoutSeconds}s")
            return ProcessResult(-1, outputBuilder.toString(), "Timeout\n$errorBuilder")
        }

        outputThread.join(1000)
        errorThread.join(1000)

        val exitCode = process.exitValue()
        println("[$logPrefix] Exit code: $exitCode")

        return ProcessResult(exitCode, outputBuilder.toString(), errorBuilder.toString())
    }
}