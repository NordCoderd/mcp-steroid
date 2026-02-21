/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.testHelper.process

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