/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid

import com.intellij.testFramework.common.timeoutRunBlocking
import com.jonnyzzz.mcpSteroid.testHelper.*
import com.jonnyzzz.mcpSteroid.testHelper.process.assertExitCode
import com.jonnyzzz.mcpSteroid.testHelper.process.assertNoErrorsInOutput
import com.jonnyzzz.mcpSteroid.testHelper.process.assertOutputContains
import java.util.*
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
    private fun claudeSession() = DockerClaudeSession.create(lifetime)

    override fun createAiSession(): AiAgentSession = claudeSession()

    /**
     * Tests that Claude CLI is properly installed in the Docker container.
     */
    fun testClaudeInstalled(): Unit = timeoutRunBlocking(180.seconds) {
        claudeSession()
            .runInContainer(listOf("--version"))
            .assertExitCode(0) { "Claude --version should succeed" }
    }

    fun testMcpServerRegistration(): Unit = timeoutRunBlocking(180.seconds) {
        val session = claudeSession()

        val mcpName = "intellij-steroid-test-${UUID.randomUUID()}"
        session.registerHttpMcp(resolveDockerUrl(), mcpName)

        session
            .runInContainer(listOf("mcp", "get", mcpName))
            .assertExitCode(0) { "MCP get command" }
            .assertOutputContains("Status:", "Connected", message = "MCP server registration")
            .assertNoErrorsInOutput(message = "MCP server registration")
    }

    override fun testDiscoversSteroidTools() {
        //needed to make test runner work
        super.testDiscoversSteroidTools()
    }

    override fun testSystemPropertyCanBeRead() {
        //needed to make test runner work
        super.testSystemPropertyCanBeRead()
    }

    override fun testExecSessionReset() {
        //needed to make test runner work
        super.testExecSessionReset()
    }

    /**
     * Tests that compilation errors from broken Kotlin code are delivered back to Claude
     * in the tool result, and Claude can report them.
     *
     * The code has a deliberate type mismatch (assigning Int to String).
     * The tool result will contain isError=true and text items with the compiler error message
     * including "type mismatch", "String", and "Int".
     *
     * We check both the raw NDJSON stream (to verify the server sent the error)
     * and the filtered agent output (to verify the agent/filter renders it).
     */
    fun testCompilationErrorsDelivered(): Unit = timeoutRunBlocking(300.seconds) {
        val session = newAiSession()

        val startedProcess = session.runPrompt(
            """
            You are testing MCP integration. You MUST call steroid_execute_code to run Kotlin code.
            Use only the MCP server named "intellij" for tool calls. Do not call list_mcp_resources.
            For steroid_execute_code, always pass project_name=${project.name}.

            Your task: execute BROKEN Kotlin code and report the compiler error.

            Call steroid_execute_code with EXACTLY this code (copy it verbatim, do NOT fix it):
            val x: String = 123
            println(x)

            This code is INTENTIONALLY broken — assigning Int 123 to a String variable.
            The tool call WILL fail with isError=true. That is expected and correct.
            Do NOT retry, do NOT fix the code, do NOT try alternative code.

            After the failed tool call, look at the tool response text for "type mismatch" or "error:".
            Print exactly these two lines:

            COMPILE_ERROR: <copy the line from the tool response that contains "type mismatch" or "error:">
            HAS_TYPE_ERROR: YES

            If the tool response does NOT mention "type mismatch", print:
            COMPILE_ERROR: <whatever error text you see>
            HAS_TYPE_ERROR: NO

            Output plain text only. No markdown, no bold, no code blocks.
            """.trimIndent(),
            timeoutSeconds = 240
        )

        // Get raw NDJSON output — this contains tool_result events with compilation errors
        val rawResult = startedProcess.awaitForProcessFinishRaw()

        // Filtered output — the ClaudeOutputFilter renders tool_result as "<< ERROR ..."
        val result = startedProcess.awaitForProcessFinish()
        val combinedOutput = result.stdout + "\n" + result.stderr

        println("=== RAW NDJSON lines with tool_result or type mismatch ===")
        rawResult.stdout.lineSequence()
            .filter { it.contains("type mismatch", ignoreCase = true) || it.contains("tool_result", ignoreCase = true) }
            .forEach { println("  $it") }
        println("=== END RAW ===")

        println("=== FILTERED AGENT OUTPUT ===")
        println(combinedOutput)
        println("=== END FILTERED ===")

        assertEquals("Exit code should be 0", 0, result.exitCode)

        // The compilation error must be in the raw NDJSON stream.
        // This proves the MCP server returned the error and Claude CLI emitted it.
        val rawOutput = rawResult.stdout + "\n" + rawResult.stderr
        assertTrue(
            "Raw NDJSON should contain 'type mismatch' from the compiler (server-side delivery check)\n" +
                    "If this fails, the MCP server or Claude CLI is not emitting the tool result.\n$rawOutput",
            rawOutput.contains("type mismatch", ignoreCase = true),
        )

        // The filtered output should also render the error (via ClaudeOutputFilter).
        assertTrue(
            "Filtered output should contain 'type mismatch' (filter rendering check)\n$combinedOutput",
            combinedOutput.contains("type mismatch", ignoreCase = true),
        )
    }

    /**
     * Tests that compiler warnings are delivered back to Claude in the tool result.
     *
     * The code uses an unchecked cast (List<Any> to List<String>) which produces a compiler
     * warning but still compiles and executes successfully. The test verifies that:
     * 1. The execution output is present (code ran successfully)
     * 2. The warning text appears in Claude's output
     */
    fun testCompilationWarningsDelivered(): Unit = timeoutRunBlocking(300.seconds) {
        val session = newAiSession()

        val result = session.runPrompt(
            """
            You are testing MCP integration. You MUST call steroid_execute_code to run Kotlin code.
            Use only the MCP server named "intellij" for tool calls. Do not call list_mcp_resources.
            For steroid_execute_code, always pass project_name=${project.name}.

            Your task: execute code that produces a compiler warning, then report it.

            Call steroid_execute_code with EXACTLY this code (copy it verbatim, do NOT modify it):
            val items: List<Any> = listOf("hello", "world")
            @Suppress("NOTHING_TO_SUPPRESS")
            val strings: List<String> = items as List<String>
            println("WARNING_TEST_VALUE: " + strings.joinToString(","))

            The code will compile and run. The tool response will contain BOTH:
            - A "Compiler Errors/Warnings:" section with "warning: unchecked cast" text
            - The println output "WARNING_TEST_VALUE: hello,world"

            After execution, print exactly these two lines:
            EXEC_RESULT: <the println output line from the tool response>
            COMPILER_WARNING: <copy the warning line that contains "warning:" from the tool response>

            If you do not see any warnings, print: COMPILER_WARNING: NONE

            Output plain text only. No markdown, no bold, no code blocks.
            """.trimIndent(),
            timeoutSeconds = 240
        )
            .assertExitCode(0) { "prompt failed" }

        val combinedOutput = result.stdout + "\n" + result.stderr

        // The code should execute successfully and produce output
        assertTrue(
            "Output should contain the execution result\n$combinedOutput",
            combinedOutput.contains("WARNING_TEST_VALUE") || combinedOutput.contains("hello,world"),
        )

        // The compiler warning "unchecked cast" or "warning" should appear in Claude's output
        assertTrue(
            "Output should mention compiler warning (unchecked cast)\n$combinedOutput",
            combinedOutput.contains("unchecked cast", ignoreCase = true)
                    || combinedOutput.contains("warning:", ignoreCase = true)
                    || Regex("""COMPILER_WARNING:\s*(?!NONE)""").containsMatchIn(combinedOutput),
        )
    }
}
