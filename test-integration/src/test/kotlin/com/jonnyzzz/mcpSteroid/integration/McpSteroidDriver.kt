/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.integration


fun IdeContainer.waitForMcpReady(timeoutSeconds: Long = 300) {
    val mcpInit =
        """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-03-26","capabilities":{},"clientInfo":{"name":"test","version":"1.0"}}}"""

    waitFor (300_000, "Wait for MCP Steroid ready") {
        val result = scope.runInContainer(
            listOf(
                "curl", "-s", "-f", "-X", "POST",
                "http://localhost:${intellijDriver.steroidPort}/mcp",
                "-H", "Content-Type: application/json",
                "-d", mcpInit,
            ),
            timeoutSeconds = 5,
        )
        result.exitCode == 0
    }
    println("[IDE-AGENT] MCP Steroid is ready")
}

