/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.intellij.mcp

import com.intellij.testFramework.common.timeoutRunBlocking
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.jonnyzzz.intellij.mcp.mcp.McpJson
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.UUID
import kotlin.time.Duration.Companion.seconds

abstract class CliIntegrationTestBase : BasePlatformTestCase() {
    override fun setUp() {
        setServerPortProperties()
        return super.setUp()
    }

    protected abstract fun newAiSession(): AiAgentSession

    /**
     * Tests that Code can discover and use our steroid_ tools.
     * Uses Docker to run CLI in isolation.
     *
     * Note: This test requires ANTHROPIC_API_KEY and uses print mode (-p)
     * which runs without user interaction.
     *
     * ============================================================================
     * TEST INTEGRITY: This test verifies ACTUAL MCP tool calls, not just mentions.
     * ============================================================================
     *
     * Success criteria (ALL must be met):
     * 1. No ERROR patterns in AI's output
     * 2. AI must list tools with "TOOL:" prefix (actual tool discovery)
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
     * Tests that AI can read a system property set in the IDE JVM via MCP execute_code.
     * This verifies the MCP server runs in the same JVM and can access system properties.
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
                Execute the following code and print the result:

                Call steroid_execute_code with this code:
                ```
                execute {
                    val value = System.getProperty("$propertyKey")
                    println("SYSPROP_VALUE: " + value)
                }
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

    fun testExecSessionReset(): Unit = timeoutRunBlocking(360.seconds) {
        val session = newAiSession()

        session.runPrompt(
            """
            You are testing MCP integration. You MUST call steroid_execute_code exactly three times, in order.
            Reason: cli session reset test, and distinct task_id values.

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
    }
}
