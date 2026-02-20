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
        super.setUp()
    }

    protected abstract fun createAiSession(): AiAgentSession

    protected open fun newAiSession(): AiAgentSession {
        return createAiSession().registerHttpMcp(resolveDockerUrl(), "intellij")
    }

    protected open fun newAiSessionViaNpx(): AiAgentSession {
        ensureNpxBuild()
        val userHome = "/home/claude"

//        val npxCommand = session.prepareNpxProxyForUrl(mcpUrl, userHome)
        TODO()
//        return createAiSession().registerNpxMcp(resolveDockerUrl(), "intellij")
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
        assertDiscoversSteroidTools(newAiSession())
    }

    open fun testDiscoversSteroidToolsViaNpx(): Unit = timeoutRunBlocking(300.seconds) {
        assertDiscoversSteroidTools(newAiSessionViaNpx())
    }

    private fun assertDiscoversSteroidTools(session: AiAgentSession) {
        val result = session.runPrompt(
            """
            You are testing an MCP server integration. You MUST use the MCP tools.
            Use only the MCP server named "intellij" for tool calls. Do not call list_mcp_resources.
            Never call any non-MCP tool (for example: strip, bash, read, write, grep, glob).
            Steps:
            1) List all MCP tools starting with "steroid_" and print each as: TOOL: <name> - <description>
            2) Call steroid_list_projects EXACTLY once and print the raw result prefixed with PROJECTS:

            Example:
            PROJECTS: { "projects": [...] }

            If you output the JSON in a code block, ensure the line starts with PROJECTS: or PROJECTS: is immediately before the block.
            Do not skip any step. If a step fails, print ERROR: <reason>.
            """,
        )
            .assertExitCode(0, "prompt")
            .assertNoErrorsInOutput(message = "prompt")

        val combinedOutput = buildString {
            appendLine(result.output)
            appendLine(result.rawOutput)
            appendLine(result.stderr)
        }
        assertTrue(
            "AI must show 'PROJECTS:' output from actual tool call.\n$combinedOutput",
            combinedOutput.contains("PROJECTS:")
        )
        assertTrue(
            "AI must include project name in output from actual tool call.\n$combinedOutput",
            combinedOutput.contains(project.name)
        )
        assertTrue(
            "AI must include project path in output from actual tool call.\n$combinedOutput",
            combinedOutput.contains(project.basePath.toString())
        )

        val output = result.output
        val marker = "PROJECTS:"
        val markerIndex = output.indexOf(marker)
        check(markerIndex >= 0) {
            "PROJECTS: line is missing from output.\n$output"
        }
        // Parse everything after PROJECTS: to support code blocks and multi-line JSON payloads.
        val payload = output.substring(markerIndex + marker.length).trim()

        val projectNames = extractProjectNamesFromPayload(payload.trim())
        assertTrue(
            "PROJECTS: line must contain project data. Payload: $payload",
            projectNames.isNotEmpty()
        )
        assertTrue(
            "PROJECTS: should contain actual project name. Payload: $payload",
            projectNames.contains(project.name)
        )
    }

    private fun extractProjectNames(projectsLine: String): List<String> {
        // Deprecated helper, kept if needed by other tests, but main logic moved above
        val payload = projectsLine.substringAfter("PROJECTS:").trim()
        return extractProjectNamesFromPayload(payload)
    }

    private fun extractProjectNamesFromPayload(payload: String): List<String> {
        var text = payload.trim()
        // Handle Markdown code blocks (e.g. ```json ... ```)
        if (text.contains("```")) {
            val content = text.substringAfter("```")
            val codeBlock = if (content.contains("```")) content.substringBefore("```") else content
            text = codeBlock.trim()
            // Remove optional language identifier
            if (text.startsWith("json", ignoreCase = true)) {
                text = text.substring(4).trim()
            }
        }

        val candidates = linkedSetOf<String>().apply {
            if (text.isNotBlank()) add(text)
            extractLeadingJsonPayload(text)?.let { add(it) }
            if (payload.isNotBlank()) add(payload)
            extractLeadingJsonPayload(payload)?.let { add(it) }
        }

        for (candidate in candidates) {
            val element = runCatching { McpJson.parseToJsonElement(candidate) }.getOrNull() ?: continue
            val names = extractProjectNamesFromElement(element)
            if (names.isNotEmpty()) return names
        }

        for (candidate in candidates) {
            val names = extractProjectNamesWithRegex(candidate)
            if (names.isNotEmpty()) return names
        }
        return emptyList()
    }

    private fun extractProjectNamesFromElement(element: JsonElement): List<String> {
        return when (element) {
            is JsonObject -> extractProjectNamesFromObject(element)
            is JsonArray -> extractProjectNamesFromArray(element)
            is JsonPrimitive -> {
                if (!element.isString) emptyList()
                else extractProjectNamesFromPayload(element.content)
            }
        }
    }

    private fun extractProjectNamesFromObject(element: JsonObject): List<String> {
        val projects = element["projects"]?.jsonArray
        if (projects != null) {
            val names = projects.mapNotNull { it.jsonObject["name"].stringValue() }
            if (names.isNotEmpty()) return names
        }
        val outputText = element["output"].stringValue()
        if (!outputText.isNullOrBlank()) {
            val names = extractProjectNamesFromPayload(outputText)
            if (names.isNotEmpty()) return names
        }

        return element.values.flatMap { child ->
            extractProjectNamesFromElement(child)
        }
    }

    private fun extractProjectNamesFromArray(element: JsonArray): List<String> {
        return element.flatMap { item ->
            if (item is JsonObject) {
                val text = item["text"].stringValue()
                if (!text.isNullOrBlank()) {
                    return@flatMap extractProjectNamesFromPayload(text)
                }
            }
            extractProjectNamesFromElement(item)
        }
    }

    private fun JsonElement?.stringValue(): String? {
        val primitive = this as? JsonPrimitive ?: return null
        return primitive.content
    }

    private fun extractProjectNamesWithRegex(payload: String): List<String> {
        val plain = Regex("\"name\"\\s*:\\s*\"([^\"]+)\"")
        val escaped = Regex("\\\\\"name\\\\\"\\s*:\\s*\\\\\"([^\\\\\"]+)\\\\\"")
        return (plain.findAll(payload).map { it.groupValues[1] } +
                escaped.findAll(payload).map { it.groupValues[1] }).toList().distinct()
    }

    private fun extractLeadingJsonPayload(text: String): String? {
        val start = text.indexOfFirst { !it.isWhitespace() && (it == '{' || it == '[') }
        if (start < 0 || start >= text.length) return null

        val open = text[start]
        val close = if (open == '{') '}' else ']'
        var depth = 0
        var inString = false
        var escaped = false

        for (index in start until text.length) {
            val ch = text[index]

            if (inString) {
                if (escaped) {
                    escaped = false
                } else if (ch == '\\') {
                    escaped = true
                } else if (ch == '"') {
                    inString = false
                }
                continue
            }

            if (ch == '"') {
                inString = true
                continue
            }

            if (ch == open) {
                depth += 1
            } else if (ch == close) {
                depth -= 1
                if (depth == 0) {
                    return text.substring(start, index + 1)
                }
            }
        }

        return null
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
        assertSystemPropertyCanBeRead(newAiSession())
    }

    open fun testSystemPropertyCanBeReadViaNpx(): Unit = timeoutRunBlocking(300.seconds) {
        assertSystemPropertyCanBeRead(newAiSessionViaNpx())
    }

    private fun assertSystemPropertyCanBeRead(session: AiAgentSession) {
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
                For steroid_execute_code, always pass project_name=PROJECT_NAME.
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
            .assertExitCode(0, "prompt")
            .assertNoErrorsInOutput(message = "prompt")
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
            .assertExitCode(0, "prompt")

        val combinedOutput = buildString {
            appendLine(result.output)
            appendLine(result.rawOutput)
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

    private fun ensureNpxBuild() {
        synchronized(npxBuildLock) {
            if (isNpxBuilt) return
            val resource = javaClass.classLoader.getResource("mcp-steroid-npx.zip")
            check(resource != null) {
                "Missing test-helper NPX artifact resource 'mcp-steroid-npx.zip'. " +
                        "Ensure :test-helper resolves :npx:npxPackageElements."
            }
            isNpxBuilt = true
        }
    }

    companion object {
        private val npxBuildLock = Any()
        @Volatile
        private var isNpxBuilt = false
    }
}
