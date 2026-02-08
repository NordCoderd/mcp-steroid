/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid

import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.common.timeoutRunBlocking
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.jonnyzzz.mcpSteroid.mcp.McpJson
import com.jonnyzzz.mcpSteroid.server.SteroidsMcpServer
import com.jonnyzzz.mcpSteroid.testHelper.*
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
        return super.setUp()
    }

    protected abstract fun newAiSession(): AiAgentSession

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
    fun testDiscoversSteroidTools(): Unit = timeoutRunBlocking(300.seconds) {
        val session = newAiSession()

        // Run Claude in print mode to discover tools
        // MCP tools must be explicitly allowed in print mode using mcp__<serverName>__* pattern
        // Permission mode must be set to bypass tool approval prompts in CI
        val result = session
            .runPrompt(
                """
            You are testing an MCP server integration. You MUST use the MCP tools.
            Use only the MCP server named "intellij" for tool calls. Do not call list_mcp_resources.
            Steps:
            1) List all MCP tools starting with "steroid_" and print each as: TOOL: <name> - <description>
            2) Call steroid_list_projects EXACTLY once and print the raw result on a single line prefixed with PROJECTS:
            Output must be plain text only. Do NOT use Markdown, bold, bullets, or code blocks.
            Do not skip any step. If a step fails, print ERROR: <reason>.
            """,
            )
            .assertExitCode(0, "prompt")
            .assertNoErrorsInOutput(message = "prompt")
            .assertOutputContains(
                "PROJECTS:",
                project.name,
                project.basePath.toString(),
                message = "AI must show 'PROJECTS:' output from actual tool call.")

        val projectsLine = result.output.lines().find { it.contains("PROJECTS:") }
            ?: error("PROJECTS: line is missing from output.")
        val projectNames = extractProjectNames(projectsLine)
        assertTrue(
            "PROJECTS: line must contain project data. Line: $projectsLine",
            projectNames.isNotEmpty()
        )
        assertTrue(
            "PROJECTS: should contain actual project name. Line: $projectsLine",
            projectNames.contains(project.name)
        )
    }

    private fun extractProjectNames(projectsLine: String): List<String> {
        val payload = projectsLine.substringAfter("PROJECTS:").trim()
        return extractProjectNamesFromPayload(payload)
    }

    private fun extractProjectNamesFromPayload(payload: String): List<String> {
        val element = runCatching { McpJson.parseToJsonElement(payload) }.getOrNull() ?: return emptyList()
        return when (element) {
            is JsonObject -> extractProjectNamesFromObject(element)
            is JsonArray -> extractProjectNamesFromContentItems(element)
            else -> emptyList()
        }
    }

    private fun extractProjectNamesFromObject(element: JsonObject): List<String> {
        val projects = element["projects"]?.jsonArray ?: return emptyList()
        return projects.mapNotNull { it.jsonObject["name"].stringValue() }
    }

    private fun extractProjectNamesFromContentItems(element: JsonArray): List<String> {
        return element.flatMap { item ->
            val text = item.jsonObject["text"].stringValue() ?: return@flatMap emptyList()
            extractProjectNamesFromPayload(text)
        }
    }

    private fun JsonElement?.stringValue(): String? {
        val primitive = this as? JsonPrimitive ?: return null
        return primitive.content
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
    fun testSystemPropertyCanBeRead(): Unit = timeoutRunBlocking(300.seconds) {
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
                Execute the following code and print the result:

                Call steroid_execute_code with this code:
                ```
                val value = System.getProperty("$propertyKey")
                println("SYSPROP_VALUE: " + value)
                ```

                After execution, extract the SYSPROP_VALUE line from the output and print it as:
                FINAL_VALUE: <the value you found>

                If you encounter any errors, print: ERROR: <description>
                """,
        )
            .assertExitCode(0, "prompt")
            .assertNoErrorsInOutput(message = "prompt")
            .assertOutputContains(
                randomValue,
                "FINAL_VALUE: $randomValue",
                message = "Output should contain the system property value '$randomValue'"
            )
    }

    protected fun runExecCode(
        session: AiAgentSession,
        code: String,
        marker: String,
    ): ProcessResult {
        return session.runPrompt(
            """
            You are testing MCP integration. You MUST use steroid_execute_code.
            Call steroid_execute_code with this code:
            ```
            $code
            ```

            After execution, find the line that contains "$marker" in the tool output
            and print it as: RESULT: <that line>

            Output must be plain text only. Do NOT use Markdown, lists, or code blocks.
            If a step fails, print ERROR: <reason>.
            """.trimIndent(),
        )
            .assertExitCode(0, "prompt")
            .assertNoErrorsInOutput(message = "prompt")
            .assertOutputContains(marker, message = "steroid_execute_code should output '$marker'")
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
    fun testExecSessionReset(): Unit = timeoutRunBlocking(360.seconds) {
        val session = newAiSession()

        session.runPrompt(
            """
            You are testing MCP integration. You MUST call steroid_execute_code exactly three times, in order.
            Use only the MCP server named "intellij" for tool calls. Do not call list_mcp_resources.
            Reason: cli session reset test, and distinct task_id values.

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
        )
            .assertExitCode(0, "prompt")
            .assertOutputContains("RESULT1:", "EXEC1_OK", message = "exec #1 should run before restart")
            .assertOutputContains("RESULT2:", "RESET_CONNECTION_BROKEN", message = "exec #2 must report connection broken (server restart kills the HTTP connection)")
            .assertOutputContains("RESULT3:", "EXEC2_OK", message = "exec #3 should run on the restarted server")
            .assertOutputContains("sessions=1", message = "restarted server should have exactly 1 fresh session (old sessions were closed, this is a new one)")
    }
}
