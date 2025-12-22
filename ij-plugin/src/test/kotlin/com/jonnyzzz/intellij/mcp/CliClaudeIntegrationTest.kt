/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.intellij.mcp

import com.intellij.testFramework.common.timeoutRunBlocking
import java.util.UUID
import kotlin.time.Duration.Companion.seconds

/**
 * Integration tests that run Claude Code CLI against the MCP server.
 *
 * These tests use Docker to isolate Claude CLI from the local system,
 * preventing side effects from MCP server registrations.
 *
 * Prerequisites:
 * - Docker must be installed and running
 * - ANTHROPIC_API_KEY must be available (either in ~/.anthropic or environment)
 *
 * The tests will automatically:
 * - Build the Docker image if needed
 * - Start a container for each test
 * - Run Claude commands inside the container
 * - Clean up the container after the test
 *
 * ============================================================================
 * IMPORTANT: TEST INTEGRITY RULES - DO NOT FAKE THESE TESTS
 * ============================================================================
 *
 * 1. NEVER ignore ERROR patterns in output - if Claude reports "ERROR:" or
 *    "**ERROR:" in its response, the test MUST fail.
 *
 * 2. ALWAYS verify actual tool calls happened - check for specific tool output
 *    patterns like "TOOL:" and "PROJECTS:" that indicate real execution.
 *
 * 3. NEVER use loose assertions like "contains steroid_" when Claude just
 *    mentions the word in an error message - verify actual successful calls.
 *
 * 4. If the test fails, report the failure. Do not modify assertions to make
 *    a failing test pass without fixing the underlying issue.
 *
 * 5. Compare with CodexCliIntegrationTest - both should have equivalent
 *    assertion strictness.
 * ============================================================================
 */
class CliClaudeIntegrationTest : CliIntegrationTestBase() {
    private fun claudeSession() = DockerClaudeSession.create(testRootDisposable)

    override fun newAiSession(): AiAgentSession = claudeSession().registerMcp(resolveDockerUrl(), "intellij")

    /**
     * Tests that Claude CLI is properly installed in the Docker container.
     */
    fun testClaudeInstalled(): Unit = timeoutRunBlocking(180.seconds) {
        claudeSession()
            .runInContainer("--version")
            .assertExitCode(0, "Claude --version should succeed")
    }

    fun testMcpServerRegistration(): Unit = timeoutRunBlocking(180.seconds) {
        val session = claudeSession()

        val mcpName = "intellij-steroid-test-${UUID.randomUUID()}"
        session.registerMcp(resolveDockerUrl(), mcpName)

        session
            .runInContainer("mcp", "get", mcpName)
            .assertExitCode(0, "MCP get command")
            .assertOutputContains("Status:", "Connected", message = "MCP server registration")
            .assertNoErrorsInOutput(message = "MCP server registration")
    }
}
