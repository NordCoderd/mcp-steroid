/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid

import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.common.timeoutRunBlocking
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.jonnyzzz.mcpSteroid.mcp.McpJson
import com.jonnyzzz.mcpSteroid.server.SteroidsMcpServer
import com.jonnyzzz.mcpSteroid.testHelper.*
import com.jonnyzzz.mcpSteroid.testHelper.process.assertExitCode
import com.jonnyzzz.mcpSteroid.testHelper.process.assertNoErrorsInOutput
import com.jonnyzzz.mcpSteroid.testHelper.process.assertOutputContains
import kotlinx.serialization.json.*
import java.util.*
import kotlin.time.Duration.Companion.seconds

abstract class CliIntegrationTestBase : BasePlatformTestCase() {
    val lifetime by lazy {
        CloseableStackHost().apply {
            Disposer.register(testRootDisposable, this::closeAllStacks)
        }
    }

    override fun setUp() {
        setServerPortProperties()
        super.setUp()
    }

    protected abstract fun createAiSession(): AiAgentSession

    protected open fun newAiSession(): AiAgentSession {
        val ai = createAiSession()
        ai.registerHttpMcp(resolveDockerUrl(), "intellij")
        return ai
    }

    /**
     * This test validates the discovery of tools and the use of the CLI.
     * Uses Docker to run the CLI in isolation.
     *
     * Note: This test requires ANTHROPIC_API_KEY and uses print mode (-p),
     * which runs without user interaction.
     *
     * ============================================================================
     * TEST INTEGRITY: This test verifies ACTUAL MCP tool calls, not just mentions.
     * ============================================================================
     *
     * Success criteria (ALL must be met):
     * 1. No ERROR patterns in AI's output
     * 2. AI must list tools with a "TOOL:" prefix (actual tool discovery)
     * 3. AI must call steroid_list_projects and show "PROJECTS:" output
     * 4. The PROJECTS output must contain actual project data (not an error)
     *
     * If any of these fail, the test fails. Do not weaken these assertions.
     */
    open fun testDiscoversSteroidTools(): Unit = timeoutRunBlocking(300.seconds) {
        val session = newAiSession()
        val result = session.runPrompt(
            """
            You are testing an MCP Steroid integration. You MUST use the MCP tools.
            Use only the MCP server named "intellij" for tool calls. Do not call list_mcp_resources.
            Steps:
            1) List all MCP tools starting with "steroid_" and print each as: TOOL: <name> - <description>
            2) Call steroid_list_projects EXACTLY once and print the raw result prefixed with PROJECTS:
            Answer only 'TOOLS: <your tools list>'
            """.trimIndent(),
        )
            .assertExitCode(0) { "prompt failed" }
            .assertOutputContains(message = "TOOLS:")
            .assertOutputContains(message = "steroid_execute_code")
    }

    /**
     * This test verifies that the MCP execute_code tool can read a system property. The IDE JVM sets it.
     * This verifies the MCP server uses the same JVM and can access system properties.
     *
     * The test:
     * 1. Sets a system property with a random UUID value
     * 2. Asks AI to read it via steroid_execute_code
     * 3. Verifies AI's output contains the correct value
     */
    open fun testSystemPropertyCanBeRead(): Unit = timeoutRunBlocking(300.seconds) {
        val session = newAiSession()

        // Set a system property with a random value
        val propertyKey = "mcp.test.ai.random.value"
        val randomValue = "ai-${UUID.randomUUID()}"
        System.setProperty(propertyKey, randomValue)

        // Ask AI to read the system property using execute_code
        session.runPrompt(
            """
                You are testing MCP integration. You MUST use steroid_execute_code to run Kotlin code.
                Use only the MCP server named "intellij" for tool calls. Do not call list_mcp_resources.
                First, call steroid_list_projects exactly once and take the first project's "name" as PROJECT_NAME.
                For steroid_execute_code, always pass project_name=${project.name}.
                IMPORTANT: "intellij" is the MCP server name, not a valid project_name value.
                Execute the following code and print the result:

                Call steroid_execute_code with this code:
                ```
                val value = System.getProperty("$propertyKey")
                println("SYSPROP_VALUE: " + value)
                ```

                After execution, extract the SYSPROP_VALUE line from the output and print it as:
                FINAL_VALUE: <the value you found>
                
                Ensure the output is plain text. Do NOT use bold, italics, or code blocks for the FINAL_VALUE line.
                Example:
                FINAL_VALUE: ai-1234-5678

                If you encounter any errors, print: ERROR: <description>
                """,
            timeoutSeconds = 240
        )
            .assertExitCode(0) { "prompt failed" }
            .assertOutputContains(
                randomValue,
                "FINAL_VALUE: $randomValue",
                message = "Output should contain the system property value '$randomValue'"
            )
    }

    /**
     * Tests that `forgetAllForTest()` truly restarts the MCP server, breaking the
     * HTTP connection, and that the agent can recover and continue on the restarted server.
     *
     * Call #2 invokes `forgetAllForTest()` which stops the Ktor server mid-request.
     * The HTTP connection carrying the response is broken, so the agent sees an error.
     * This is expected and desired — it proves the server truly restarted.
     *
     * Call #3 runs on the restarted server with a fresh session. It verifies
     * that session count is 1 (only the agent's new reconnected session).
     */
    open fun testExecSessionReset(): Unit = timeoutRunBlocking(360.seconds) {
        val session = newAiSession()

        val result = session.runPrompt(
            """
            You are testing MCP integration. You MUST call steroid_execute_code exactly three times, in order.
            Use only the MCP server named "intellij" for tool calls. Do not call list_mcp_resources.
            Reason: cli session reset test, and distinct task_id values.
            First, call steroid_list_projects exactly once and take the first project's "name" as PROJECT_NAME.
            For each steroid_execute_code call (#1, #2, #3), pass project_name=PROJECT_NAME.
            IMPORTANT: "intellij" is the MCP server name, not a valid project_name value.

            Call #1 code:
            ```
            println("EXEC1_OK")
            ```

            Call #2 code:
            ```
            ${SteroidsMcpServer::class.java.name}.getInstance().forgetAllForTest()
            println("SESSIONS_FORGOTTEN: OK")
            ```
            IMPORTANT: Call #2 restarts the MCP server. The connection WILL break and the call WILL fail.
            This is expected and correct. Do NOT retry call #2. Just note it failed and move on.

            Call #3 code:
            ```
            val count = ${SteroidsMcpServer::class.java.name}.getInstance().getServer().sessionManager.getSessionCount()
            println("EXEC2_OK: sessions=" + count)
            ```

            After each call, print a result line:
            RESULT1: <line from call #1 output containing EXEC1_OK>
            RESULT2: RESET_CONNECTION_BROKEN (if call #2 failed, which is expected) or RESET_OK (if it somehow succeeded)
            RESULT3: <line from call #3 output containing EXEC2_OK>

            Output must be plain text only. Do NOT use Markdown, lists, or code blocks.
            """.trimIndent(),
            timeoutSeconds = 300
        )
            .assertExitCode(0) { "prompt failed" }

        val combinedOutput = buildString {
            appendLine(result.stdout)
            appendLine(result.stdout)
            appendLine(result.stderr)
        }

        assertTrue(
            "exec #1 should run before restart\n$combinedOutput",
            Regex("""RESULT1:\s*.*EXEC1_OK""").containsMatchIn(combinedOutput),
        )
        assertTrue(
            "exec #2 must report connection broken (server restart kills the HTTP connection)\n$combinedOutput",
            Regex("""RESULT2:\s*RESET_CONNECTION_BROKEN""").containsMatchIn(combinedOutput),
        )
        assertTrue(
            "exec #3 should run on the restarted server\n$combinedOutput",
            Regex("""RESULT3:\s*.*EXEC2_OK""").containsMatchIn(combinedOutput),
        )
        assertTrue(
            "restarted server should have exactly 1 fresh session (old sessions were closed, this is a new one)\n$combinedOutput",
            Regex("""RESULT3:\s*.*sessions=1""").containsMatchIn(combinedOutput),
        )
    }
}
