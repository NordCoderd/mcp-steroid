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
 * Supports filtering secrets from log output.
 */
class ProcessRunner {
    private val secretPatterns = mutableListOf<String>()

    /**
     * Add a secret pattern that should be filtered from log output.
     * The secret will be replaced with [REDACTED] in all logged output.
     */
    fun addSecretFilter(secret: String) {
        if (secret.isNotBlank()) {
            secretPatterns.add(secret)
        }
    }

    /**
     * Filter secrets from text, replacing them with [REDACTED].
     */
    private fun filterSecrets(text: String): String {
        var result = text
        for (pattern in secretPatterns) {
            if (pattern.isNotBlank()) {
                result = result.replace(pattern, "[REDACTED]")
            }
        }
        return result
    }

    /**
     * Run a process and log all output with prefixes.
     * Secrets are filtered from log output but preserved in returned ProcessResult.
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
        // Filter secrets from command line and description for logging
        val filteredCommand = command.map { filterSecrets(it) }
        val filteredDescription = filterSecrets(description)
        println("[$logPrefix] $filteredDescription: ${filteredCommand.joinToString(" ")}")

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
                println("[$logPrefix OUT] ${filterSecrets(line)}")
                outputBuilder.appendLine(line)
            }
        }
        val errorThread = Thread {
            process.errorStream.reader().forEachLine { line ->
                println("[$logPrefix ERR] ${filterSecrets(line)}")
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

    companion object {
        /**
         * Default instance without any secret filtering.
         * For backwards compatibility with existing code.
         */
        private val defaultInstance = ProcessRunner()

        /**
         * Run a process using the default instance (no secret filtering).
         */
        fun run(
            command: List<String>,
            description: String,
            workingDir: File,
            timeoutSeconds: Long = 30,
            logPrefix: String = "PROCESS"
        ): ProcessResult = defaultInstance.run(command, description, workingDir, timeoutSeconds, logPrefix)
    }
}
