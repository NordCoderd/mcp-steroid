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
 *   - claude (default): Claude NDJSON filter
 *   - codex: Codex NDJSON filter
 *   - gemini: Gemini NDJSON filter
 */
fun main(args: Array<String>) {
    val filterType = args.getOrNull(0) ?: "claude"

    val filter = when (filterType.lowercase()) {
        "claude" -> ClaudeOutputFilter()
        "codex" -> CodexOutputFilter()
        "gemini" -> GeminiOutputFilter()
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
          claude    Claude NDJSON filter (default)
          codex     Codex NDJSON filter
          gemini    Gemini NDJSON filter

        Options:
          --help, -h     Show this help message
          --version, -v  Show version information
    """.trimIndent())
}
