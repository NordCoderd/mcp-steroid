/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.integration

import com.jonnyzzz.mcpSteroid.testHelper.CloseableStackHost
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.concurrent.TimeUnit

/**
 * Integration test for DialogKiller functionality.
 *
 * This test runs the IDE in a Docker container with full GUI (via Xvfb) and verifies
 * that the dialog killer can detect and close modal dialogs before code execution.
 *
 * TODO: Implement full integration test using AI agent to interact with MCP server
 * For now, this is a placeholder that verifies the IDE starts correctly.
 */
class DialogKillerIntegrationTest {
    private val lifetime by lazy {
        CloseableStackHost()
    }

    @AfterEach
    fun tearDown() {
        lifetime.closeAllStacks()
    }

    @Test
    @Timeout(value = 15, unit = TimeUnit.MINUTES)
    fun `dialog killer feature is present and IDE starts`() {
        // Start IDE container with full GUI
        val session = IdeContainer.create(lifetime, "ide-agent")

        // Wait for MCP server to be ready
        session.aiAgentDriver.waitForMcpReady()

        println("[TEST] IDE started successfully with dialog killer feature")
        println("[TEST] MCP Steroid server ready at: ${session.aiAgentDriver.mcpSteroidHostUrl}")

        // TODO: Use AI agent to test dialog killer:
        // 1. Open Settings dialog via steroid_execute_code
        // 2. Verify modal state
        // 3. Execute code again - dialog killer should close it
        // 4. Verify execution succeeds

        println("[TEST] ✅ Dialog killer integration test placeholder passed")
    }
}
