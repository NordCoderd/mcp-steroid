/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.testHelper

import com.jonnyzzz.mcpSteroid.aiAgents.StdioMcpCommand
import com.jonnyzzz.mcpSteroid.filter.AgentProgressOutputFilter
import com.jonnyzzz.mcpSteroid.testHelper.process.ProcessResult
import com.jonnyzzz.mcpSteroid.testHelper.process.StartedProcess

interface AiAgentSession {
    val displayName: String

    /**
     * Run codex exec for non-interactive mode.
     */
    fun runPrompt(
        prompt: String,
        timeoutSeconds: Long = 120
    ): AiStartedProcess

    fun registerHttpMcp(mcpUrl: String, mcpName: String)

    fun registerNpxMcp(npxCommand: StdioMcpCommand, mcpName: String)
}


interface AiStartedProcess : StartedProcess {
    val outputFilter: AgentProgressOutputFilter

    /**
     * Returns unprocessed messages
     */
    fun awaitForProcessFinishRaw(): ProcessResult
}
