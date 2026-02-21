/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.testHelper

import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.util.concurrent.TimeUnit

/**
 * Result from running a process.
 */
interface ProcessResult {
    val exitCode: Int?
    val output: String
    val stderr: String

    /**
     * Raw unprocessed output from the process.
     *
     * For agents that post-process output (e.g. Claude's stream-json mode),
     * [output] contains the extracted final text for assertions, while
     * [rawOutput] preserves the full original output (e.g. NDJSON events).
     *
     * For agents without post-processing, [rawOutput] equals [output].
     */
    val rawOutput: String get() = output
}

data class ProcessResultValue(
    override val exitCode: Int,
    override val output: String,
    override val stderr: String,
    override val rawOutput: String = output,
) : ProcessResult

fun ProcessResult.assertOutputContains(vararg expectedOutput: String, message: String = "") = apply {
    for (s in expectedOutput) {
        check(output.contains(s) || stderr.contains(s)) {
            "Process $message output must contain $s\n$this"
        }
    }
}

fun ProcessResult.assertExitCode(expectedExitCode: Int, message: String = "") = apply {
    check(exitCode == expectedExitCode) {
        "Process $message exit code is $exitCode != $expectedExitCode\n$this"
    }
}

fun ProcessResult.assertExitCode(expectedExitCode: Int, message: ProcessResult.() -> String) = apply {
    check(exitCode == expectedExitCode) {
        "Process ${message()} exit code is $exitCode != $expectedExitCode\n$this"
    }
}

fun ProcessResult.assertNoErrorsInOutput(message: String) = apply {
    val combined = output + "\n" + stderr

    // Check for explicit ERROR patterns (case-insensitive)
    val errorPatterns = listOf(
        Regex("(?i)\\*\\*ERROR:", RegexOption.MULTILINE),
        Regex("(?i)^ERROR:", RegexOption.MULTILINE),
        Regex("(?i)tool .* is not available", RegexOption.IGNORE_CASE),
        Regex("(?i)not registered or accessible", RegexOption.IGNORE_CASE),
        Regex("(?i)failed to connect", RegexOption.IGNORE_CASE),
    )

    for (pattern in errorPatterns) {
        val match = pattern.find(combined)
        check(match == null) {
            "$message: Found error pattern '${pattern.pattern}' in output. " +
                    "Match: '${match?.value}'. Full output:\n$combined"
        }
    }
}

fun ProcessResult.assertNoMessageInOutput(messageRegex: String) = apply {
    val combined = output + "\n" + stderr

    // Check for explicit ERROR patterns (case-insensitive)
    val errorPatterns = listOf(
        Regex(messageRegex, RegexOption.IGNORE_CASE),
    )

    for (pattern in errorPatterns) {
        val match = pattern.find(combined)
        check(match == null) {
            "$messageRegex: Found error pattern '${pattern.pattern}' in output. " +
                    "Match: '${match?.value}'. Full output:\n$combined"
        }
    }
}

/**
 * Request configuration for running a process.
 * Use the builder pattern to construct instances.
 */
data class ProcessRunRequest(
    val command: List<String>,
    val description: String,
    val workingDir: File,
    val timeoutSeconds: Long = 30,
    val quietly: Boolean = false,
    val stdin: InputStream,
) {
    open class Builder {
        protected var command: List<String>? = null
        protected var description: String? = null
        protected var workingDir: File? = null
        protected var timeoutSeconds: Long = 30
        protected var quietly: Boolean = false
        protected var stdin: InputStream = ByteArrayInputStream(ByteArray(0))

        open fun command(command: List<String>) = apply { this.command = command }
        open fun command(builder: MutableList<String>.() -> Unit) = command(buildList(builder))
        open fun command(vararg command: String) = command(command.toList())

        open fun description(description: String) = apply { this.description = description }

        open fun workingDir(workingDir: File) = apply { this.workingDir = workingDir }

        open fun timeoutSeconds(timeoutSeconds: Long) = apply { this.timeoutSeconds = timeoutSeconds }

        open fun quietly(quietly: Boolean) = apply { this.quietly = quietly }
        open fun quietly() = quietly(true)

        open fun stdin(stdin: InputStream) = apply { this.stdin = stdin }
        open fun stdin(stdin: ByteArray) = stdin(stdin.inputStream())
        open fun stdin(stdin: String) = stdin(stdin.toByteArray())

        open fun build(): ProcessRunRequest {
            return ProcessRunRequest(
                command = command ?: error("command is required"),
                description = description ?: error("description is required"),
                workingDir = workingDir ?: error("workingDir is required"),
                timeoutSeconds = timeoutSeconds,
                quietly = quietly,
                stdin = stdin,
            )
        }
    }

    companion object {
        fun builder() = Builder()
    }
}

fun ProcessRunRequest.Builder.runProcess(processRunner: ProcessRunner) = build().runProcess(processRunner)
fun ProcessRunRequest.runProcess(runner: ProcessRunner) = runner.runProcess(this)

/**
 * Utility for running processes with consistent logging.
 * All output is logged to stdout with prefixes for easy debugging.
 * Supports filtering secrets from log output.
 */
class ProcessRunner(
    private val logPrefix: String,
    private val secretPatterns: List<String>,
) {
    /**
     * Filter secrets from text, replacing them with REDACTED.
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
     * Run a process using the request configuration.
     * This is the primary method for running processes.
     * Secrets are filtered from log output but preserved in returned ProcessResult.
     *
     * @param request The process run request with all configuration
     */
    fun runProcess(request: ProcessRunRequest): ProcessResult {
        // Filter secrets from command line and description for logging
        val filteredCommand = request.command.map { filterSecrets(it) }
        val filteredDescription = filterSecrets(request.description)
        println("[$logPrefix] $filteredDescription")

        val processBuilder = ProcessBuilder(request.command)
        processBuilder.directory(request.workingDir)
        processBuilder.redirectInput(ProcessBuilder.Redirect.PIPE)
        processBuilder.redirectOutput(ProcessBuilder.Redirect.PIPE)
        processBuilder.redirectError(ProcessBuilder.Redirect.PIPE)

        val process = processBuilder.start()

        fun readOutput(stream: InputStream, prefix: String, target: StringBuilder) {
            try {
                stream.reader().use { reader ->
                    while (process.isAlive) {
                        Thread.sleep(100)
                        reader.forEachLine { line ->
                            val filterSecrets = filterSecrets(line)
                            if (!request.quietly) {
                                println("[$prefix] $filterSecrets")
                            }
                            target.appendLine(filterSecrets)
                        }
                    }
                }
            } catch (_: Exception) {
                // Ignore stream read errors
            }
        }

        val outputBuilder = StringBuilder()
        val errorBuilder = StringBuilder()

        // Thread for copying stdin to the process
        val stdinThread = Thread {
            try {
                request.stdin.use { input ->
                    process.outputStream.use { output ->
                        input.copyTo(output)
                    }
                }
            } catch (e: Exception) {
                if (!request.quietly) {
                    println("[$logPrefix] stdin copy error: ${e.message}")
                }
            }
        }

        val outputThread = Thread {
            readOutput(process.inputStream, "$logPrefix OUT", outputBuilder)
        }

        val errorThread = Thread {
            readOutput(process.errorStream, "$logPrefix ERR", errorBuilder)
        }

        stdinThread.start()
        outputThread.start()
        errorThread.start()

        val completed = process.waitFor(request.timeoutSeconds, TimeUnit.SECONDS)

        if (!completed) {
            process.destroyForcibly()
            println("[$logPrefix] Timed out after ${request.timeoutSeconds}s")
            return ProcessResultValue(-1, outputBuilder.toString(), "Timeout\n$errorBuilder")
        }

        stdinThread.join(1000)
        outputThread.join(1000)
        errorThread.join(1000)

        val exitCode = process.exitValue()
        println("[$logPrefix] Exit code: $exitCode")
        return ProcessResultValue(exitCode, outputBuilder.toString(), errorBuilder.toString())
    }
}
