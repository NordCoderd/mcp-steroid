/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.filter

import kotlin.system.exitProcess

/**
 * Entry point for the agent output filter executable JAR.
 *
 * Usage:
 *   java -jar agent-output-filter.jar [filter-type]
 *
 * Filter types:
 *   - stream-json, claude (default): Claude stream-json NDJSON filter
 *   - codex: Codex CLI --json NDJSON filter
 *   - gemini, gemini-json, gemini-stream-json: Gemini CLI stream-json NDJSON filter
 */
fun main(args: Array<String>) {
    val filterType = args.getOrNull(0) ?: "stream-json"

    val filter = when (filterType.lowercase()) {
        "stream-json", "claude" -> ClaudeStreamJsonFilter()
        "codex" -> CodexJsonFilter()
        "gemini", "gemini-json", "gemini-stream-json" -> GeminiStreamJsonFilter()
        "--help", "-h" -> {
            printHelp()
            exitProcess(0)
        }
        "--version", "-v" -> {
            println("agent-output-filter 1.0.0")
            exitProcess(0)
        }
        else -> {
            System.err.println("Error: Unknown filter type '$filterType'")
            System.err.println()
            printHelp()
            exitProcess(1)
        }
    }

    try {
        filter.process(System.`in`, System.out)
    } catch (e: Exception) {
        System.err.println("Error processing input: ${e.message}")
        e.printStackTrace(System.err)
        exitProcess(1)
    }
}

private fun printHelp() {
    println("""
        Agent Output Filter - Convert AI agent NDJSON output to human-readable format

        Usage:
          agent-output-filter [filter-type]

        Filter types:
          stream-json, claude    Claude stream-json NDJSON filter (default)
          codex                  Codex CLI --json NDJSON filter
          gemini, gemini-json   Gemini CLI stream-json NDJSON filter

        Options:
          --help, -h             Show this help message
          --version, -v          Show version information

        Examples:
          cat agent-output.ndjson | agent-output-filter stream-json
          codex exec --json ... | agent-output-filter codex
          gemini chat --output-format stream-json ... | agent-output-filter gemini
    """.trimIndent())
}
