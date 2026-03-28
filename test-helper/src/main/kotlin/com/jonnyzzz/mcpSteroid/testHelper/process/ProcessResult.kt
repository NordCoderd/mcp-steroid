/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.testHelper.process

/**
 * Result from running a process.
 */
interface ProcessResult {
    val exitCode: Int?
    val stdout: String
    val stderr: String
}

fun <T : ProcessResult> T.assertOutputContains(vararg expectedOutput: String, message: String = ""): T = apply {
    for (s in expectedOutput) {
        check(stdout.contains(s) || stderr.contains(s)) {
            "Process $message output must contain $s\n$this"
        }
    }
}

fun <T : ProcessResult> T.assertExitCode(expectedExitCode: Int, message: String = ""): T = apply {
    check(exitCode == expectedExitCode) {
        "Process $message exit code is $exitCode != $expectedExitCode\n$this"
    }
}

fun <T : ProcessResult> T.assertExitCode(expectedExitCode: Int, message: ProcessResult.() -> String): T = apply {
    check(exitCode == expectedExitCode) {
        "Process ${message()} exit code is $exitCode != $expectedExitCode\n$this"
    }
}

fun <T : ProcessResult> T.assertNoErrorsInOutput(message: String): T = apply {
    val combined = stdout + "\n" + stderr

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

fun <T : ProcessResult> T.assertNoMessageInOutput(messageRegex: String): T = apply {
    val combined = stdout + "\n" + stderr

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
 * Result from running an AI agent process.
 * Contains both filtered (human-readable) output and raw (NDJSON) output.
 */
class AiProcessResult(
    override val exitCode: Int?,
    override val stdout: String,
    override val stderr: String,
    /** Raw unfiltered stdout (NDJSON) before output filter was applied */
    val rawStdout: String,
) : ProcessResult {
    override fun toString(): String =
        "AiProcessResult(exitCode=$exitCode, stdout=${stdout.take(500)}, stderr=${stderr.take(500)})"
}
