/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.integration.infra

import com.jonnyzzz.mcpSteroid.aiAgents.StdioMcpCommand
import com.jonnyzzz.mcpSteroid.filter.filterText
import com.jonnyzzz.mcpSteroid.testHelper.AiAgentSession
import com.jonnyzzz.mcpSteroid.testHelper.AiStartedProcess
import com.jonnyzzz.mcpSteroid.testHelper.process.ProcessStreamType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Wraps an [AiAgentSession] to display agent activity in the [ConsoleDriver].
 *
 * Before running a prompt, the prompt text is shown in bright ANSI color.
 * During execution, agent output is pumped to the console in real-time:
 * STDOUT lines are decoded through the agent-specific output filter (NDJSON → readable text);
 * STDERR lines are forwarded directly.
 */
class ConsoleAwareAgentSession(
    private val delegate: AiAgentSession,
    private val console: ConsoleDriver,
    private val agentName: String,
) : AiAgentSession {
    override val displayName: String
        get() = delegate.displayName

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun runPrompt(prompt: String, timeoutSeconds: Long): AiStartedProcess {
        console.writePrompt(agentName, prompt)
        console.writeInfo("Running $agentName...")

        val aiProcess = delegate.runPrompt(prompt, timeoutSeconds)

        // Pump process output to the console in real-time.
        // Each STDOUT line is decoded through the agent-specific NDJSON filter;
        // STDERR lines are forwarded as-is.
        scope.launch {
            aiProcess.messagesFlow.collect { streamLine ->
                val ignore = when (streamLine.type) {
                    ProcessStreamType.STDOUT -> {
                        val filtered = aiProcess.outputFilter.filterText(streamLine.line)
                        filtered.lines().forEach { console.writeLine(it) }
                    }

                    ProcessStreamType.STDERR -> {
                        console.writeLine("[stderr] ${streamLine.line}")
                    }

                    ProcessStreamType.INFO -> {
                        console.writeLine("[INFO] ${streamLine.line}")
                    }
                }
            }
        }

        return aiProcess
    }

    override fun registerHttpMcp(mcpUrl: String, mcpName: String) {
        delegate.registerHttpMcp(mcpUrl, mcpName)
    }

    override fun registerNpxMcp(npxCommand: StdioMcpCommand, mcpName: String) {
        delegate.registerNpxMcp(npxCommand, mcpName)
    }
}
