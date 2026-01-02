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

        session.runPrompt(
            """
            You are testing MCP integration. You MUST call steroid_execute_code exactly three times, in order.
            Use project_name: intellij-mcp-steroid, reason: cli session reset test, and distinct task_id values.

            Call #1 code:
            execute {
                println("EXEC1_OK")
            }

            Call #2 code:
            import com.jonnyzzz.intellij.mcp.server.SteroidsMcpServer

            execute {
                val server = SteroidsMcpServer.getInstance().getServer()
                val forgotten = server.sessionManager.forgetAllSessionsForTest()
                println("SESSIONS_FORGOTTEN: " + forgotten)
            }

            Call #3 code:
            execute {
                println("EXEC2_OK")
            }

            After each call, extract the output line containing the marker and print:
            RESULT1: <line with EXEC1_OK>
            RESULT2: <line with SESSIONS_FORGOTTEN:>
            RESULT3: <line with EXEC2_OK>

            Output must be plain text only. Do NOT use Markdown, lists, or code blocks.
            If any step fails, print ERROR: <reason>.
            """.trimIndent(),
        )
            .assertExitCode(0, "prompt")
            .assertNoErrorsInOutput(message = "prompt")
            .assertOutputContains("RESULT1:", "EXEC1_OK", message = "exec #1 should run")
            .assertOutputContains("RESULT2:", "SESSIONS_FORGOTTEN:", message = "exec #2 should run")
            .assertOutputContains("RESULT3:", "EXEC2_OK", message = "exec #3 should run")

        session.runInContainer("mcp", "list")
            .assertExitCode(0, "mcp list")
            .assertNoErrorsInOutput(message = "mcp list")
            .assertOutputContains("intellij", message = "mcp list should contain registered server")
    }
}
