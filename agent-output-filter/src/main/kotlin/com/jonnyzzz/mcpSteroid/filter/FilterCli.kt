/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.filter

fun main(args: Array<String>) {
    val agentType = args.firstOrNull()
        ?: error("Usage: agent-output-filter <claude|codex|gemini>")
    val filter: AgentProgressOutputFilter = when (agentType.lowercase()) {
        "claude" -> ClaudeOutputFilter()
        "codex" -> CodexOutputFilter()
        "gemini" -> GeminiOutputFilter()
        else -> error("Unknown agent type: $agentType. Supported: claude, codex, gemini")
    }
    filter.process(System.`in`, System.out)
}
