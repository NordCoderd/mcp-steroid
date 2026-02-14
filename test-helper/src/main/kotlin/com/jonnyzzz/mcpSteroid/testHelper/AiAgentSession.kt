/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.testHelper

interface AiAgentSession {
    /**
     * Run codex exec for non-interactive mode.
     */
    fun runPrompt(
        prompt: String,
        timeoutSeconds: Long = 120
    ): ProcessResult

    fun registerHttpMcp(mcpUrl: String, mcpName: String): AiAgentSession

    fun registerNpxMcp(mcpUrl: String, mcpName: String): AiAgentSession

    @Deprecated(
        message = "Use registerHttpMcp for explicit HTTP registration",
        replaceWith = ReplaceWith("registerHttpMcp(mcpUrl, mcpName)")
    )
    fun registerMcp(mcpUrl: String, mcpName: String): AiAgentSession = registerHttpMcp(mcpUrl, mcpName)

    @Deprecated(
        message = "Use registerNpxMcp for explicit NPX stdio registration",
        replaceWith = ReplaceWith("registerNpxMcp(mcpUrl, mcpName)")
    )
    fun registerMcpViaNpx(mcpUrl: String, mcpName: String): AiAgentSession = registerNpxMcp(mcpUrl, mcpName)
}
