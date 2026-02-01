/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid

import junit.framework.TestCase.assertEquals
import junit.framework.TestCase.assertFalse
import org.junit.Assert
import java.io.File
import java.io.InputStream
import java.util.concurrent.TimeUnit

/**
 * Result from running a process.
 */
data class ProcessResult(
    val exitCode: Int,
    val output: String,
    val stderr: String
)

fun ProcessResult.assertOutputContains(vararg expectedOutput: String, message: String = "") = apply {
    for (s in expectedOutput) {
        Assert.assertTrue("Process $message output must container $s\n$this", output.contains(s) || stderr.contains(s))
    }
}

fun ProcessResult.assertExitCode(expectedExitCode: Int, message: String = "") = apply {
    assertEquals("Process $message exist code is $exitCode != $expectedExitCode", expectedExitCode, exitCode)
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
        assertFalse(
            "$message: Found error pattern '${pattern.pattern}' in output. " +
                    "Match: '${match?.value}'. Full output:\n$combined",
            match != null
        )
    }
}

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
    ): ProcessResult {
        // Filter secrets from command line and description for logging
        val filteredCommand = command.map { filterSecrets(it) }
        val filteredDescription = filterSecrets(description)
        println("[$logPrefix] $filteredDescription")

        val processBuilder = ProcessBuilder(command)
        processBuilder.directory(workingDir)
        processBuilder.redirectInput(ProcessBuilder.Redirect.PIPE)
        processBuilder.redirectOutput(ProcessBuilder.Redirect.PIPE)
        processBuilder.redirectError(ProcessBuilder.Redirect.PIPE)

        val process = processBuilder.start()
        process.outputStream.close()

        fun readOutput(stream: InputStream, prefix: String, target: StringBuilder) {
            runCatching {
                stream.reader().use { reader ->
                    while (process.isAlive) {
                        Thread.sleep(100)
                        reader.forEachLine { line ->
                            val filterSecrets = filterSecrets(line)
                            println("[$prefix] $filterSecrets")
                            target.appendLine(filterSecrets)
                        }
                    }
                }
            }
        }


        val outputBuilder = StringBuilder()
        val errorBuilder = StringBuilder()

        val outputThread = Thread {
            readOutput(process.inputStream, "$logPrefix OUT", outputBuilder)
        }

        val errorThread = Thread {
            readOutput(process.errorStream, "$logPrefix ERR", errorBuilder)
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
