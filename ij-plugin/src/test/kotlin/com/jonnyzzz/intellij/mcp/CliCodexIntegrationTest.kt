/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.intellij.mcp

import com.intellij.testFramework.common.timeoutRunBlocking
import java.util.UUID
import kotlin.time.Duration.Companion.seconds

/**
 * Integration tests that run OpenAI Codex CLI against the MCP server.
 *
 * These tests use Docker to isolate Codex CLI from the local system,
 * preventing side effects from MCP server registrations.
 *
 * Prerequisites:
 * - Docker must be installed and running
 * - OPENAI_API_KEY must be available (either in ~/.openai or environment)
 *
 * The tests will automatically:
 * - Build the Docker image if needed
 * - Start a container for each test
 * - Run Codex commands inside the container
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
class CliCodexIntegrationTest : CliIntegrationTestBase() {
    private fun codexSession() = DockerCodexSession.create(testRootDisposable)

    override fun newAiSession(): AiAgentSession = codexSession().registerMcp(resolveDockerUrl(), "intellij").toAiSession()

    /**
     * Tests that Codex CLI is properly installed in the Docker container.
     */
    fun testCodexInstalled(): Unit = timeoutRunBlocking(180.seconds) {
        val session = codexSession()

        // Check codex version
        session.runInContainer("--version")
            .assertNoErrorsInOutput("version command")
            .assertExitCode(0, "version command")
    }

    /**
     * Tests that MCP server can be added via `codex mcp add` command.
     * Codex uses a different syntax than Claude CLI.
     *
     * For HTTP-based servers, Codex uses: codex mcp add <name> --transport http --url <url>
     *
     * Note: If Codex CLI doesn't have mcp subcommand, the test is skipped.
     */
    fun testMcpServerAddCommand(): Unit = timeoutRunBlocking(180.seconds) {
        val session = codexSession()

        val mcpName = "intellij-steroid-test-${UUID.randomUUID()}"
        session.registerMcp(resolveDockerUrl(), mcpName)

        session
            .runInContainer("mcp", "get", mcpName)
            .assertExitCode(0, "MCP get command")
            .assertOutputContains(
                "enabled: true",
                "transport: streamable_http",
                message = "MCP server registration")
            .assertNoErrorsInOutput(message = "MCP server registration")
    }

    fun testExecSessionResetAndMcpList(): Unit = timeoutRunBlocking(360.seconds) {
        val session = codexSession().registerMcp(resolveDockerUrl(), "intellij")

        runExecCode(
            session,
            """
            execute {
                println("EXEC1_OK")
            }
            """.trimIndent(),
            "EXEC1_OK",
        )

        runExecCode(
            session,
            """
            import com.jonnyzzz.intellij.mcp.server.SteroidsMcpServer

            execute {
                val server = SteroidsMcpServer.getInstance().getServer()
                val sessionIds = server.sessionManager.getAllSessions().map { it.id }
                sessionIds.forEach { server.sessionManager.removeSession(it) }
                println("SESSIONS_CLEARED: " + sessionIds.size)
            }
            """.trimIndent(),
            "SESSIONS_CLEARED:",
        )

        runExecCode(
            session,
            """
            execute {
                println("EXEC2_OK")
            }
            """.trimIndent(),
            "EXEC2_OK",
        )

        session.runInContainer("mcp", "list")
            .assertExitCode(0, "mcp list")
            .assertNoErrorsInOutput(message = "mcp list")
            .assertOutputContains("intellij", message = "mcp list should contain registered server")
    }
}
