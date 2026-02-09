/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.integration

import com.jonnyzzz.mcpSteroid.testHelper.AiAgentSession
import com.jonnyzzz.mcpSteroid.testHelper.CloseableStackHost
import com.jonnyzzz.mcpSteroid.testHelper.assertExitCode
import com.jonnyzzz.mcpSteroid.testHelper.assertOutputContains
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.concurrent.TimeUnit

/**
 * Integration test: debugger demo from ai-tests/07-debugger.md.
 *
 * Runs AI agents inside a Docker container with IntelliJ IDEA + MCP Steroid plugin.
 * The agent is asked to debug DemoByJonnyzzz.kt and find the sortedByDescending bug.
 *
 * The container is kept alive after the test for debugging (removed on next run).
 * Video is always recorded and mounted to the host for live preview.
 */
class DebuggerDemoTest {

    @Test
    @Timeout(value = 20, unit = TimeUnit.MINUTES)
    fun `claude finds sortedByDescending bug via debugger`() = runDebuggerDemo("claude")

    @Test
    @Timeout(value = 20, unit = TimeUnit.MINUTES)
    fun `codex finds sortedByDescending bug via debugger`() = runDebuggerDemo("codex")

    @Test
    @Timeout(value = 20, unit = TimeUnit.MINUTES)
    fun `gemini finds sortedByDescending bug via debugger`() = runDebuggerDemo("gemini")

    private fun runDebuggerDemo(agentName: String) {
        val agent: AiAgentSession? = session.aiAgentDriver.aiAgents[agentName]
        assumeTrue(agent != null, "Agent '$agentName' is not configured")

        val prompt = buildString {
            appendLine("Debug the file DemoByJonnyzzz.kt in this project to find the bug in the leaderboard function.")
            appendLine()
            appendLine("Execution strategy (mandatory):")
            appendLine("- First read MCP resource: mcp-steroid://debugger/overview")
            appendLine("- First call steroid_list_projects once and use that exact project_name in every steroid_execute_code call")
            appendLine("- Use short, stateful steroid_execute_code calls (no huge monolithic script)")
            appendLine("- Use at most 4 steroid_execute_code calls total")
            appendLine("- If debugger setup fails with compiler/runtime errors, stop retrying broad scripts and do one final source-based diagnosis call")
            appendLine()
            appendLine("Requirements:")
            appendLine("1. Find and open DemoByJonnyzzz.kt in the project")
            appendLine("2. Set a breakpoint on the line containing sortedByDescending (do not rely on a fixed line number)")
            appendLine("3. Create a run configuration for DemoByJonnyzzzKt and start the debugger")
            appendLine("4. Wait for the debugger to suspend at the breakpoint")
            appendLine("5. Evaluate variables and expressions to understand the bug")
            appendLine("6. Step over the line and observe what happens")
            appendLine("7. Identify the root cause of the bug")
            appendLine("8. The root cause must be about sortedByDescending returning a new list whose result is ignored/not assigned")
            appendLine()
            appendLine("Use only steroid_list_projects (for project discovery) and steroid_execute_code (for IDE actions).")
            appendLine("Do not use screenshots or UI input tools.")
            appendLine("After your first steroid_execute_code call, include this in your final response:")
            appendLine("TOOL_EVIDENCE: <copy the line starting with Execution ID: ...>")
            appendLine()
            appendLine("At the end of your analysis, output these markers on separate lines:")
            appendLine("BUG_FOUND: yes")
            appendLine("BUG_LINE: <copy the exact buggy source line>")
            appendLine("ROOT_CAUSE: <one line description>")
        }

        val result = agent!!.runPrompt(prompt, timeoutSeconds = 600)
        val combined = result.output + "\n" + result.stderr

        // If CLI timed out but the agent already emitted required markers, keep validating the output.
        val hasFinalMarkers = combined.contains("BUG_FOUND:", ignoreCase = true) &&
                combined.contains("ROOT_CAUSE:", ignoreCase = true)
        if (result.exitCode != 0 && !hasFinalMarkers) {
            result.assertExitCode(0, message = "debugger demo")
        }

        // Agent must show evidence of MCP Steroid execute_code usage
        assertUsedExecuteCodeEvidence(combined)

        check(combined.contains("BUG_FOUND: yes", ignoreCase = true)) {
            "Agent did not output required marker 'BUG_FOUND: yes'.\nOutput:\n$combined"
        }

        val bugLine = findMarkerValue(combined, "BUG_LINE")
        check(bugLine != null) {
            "Agent did not output required marker 'BUG_LINE:'.\nOutput:\n$combined"
        }
        check(bugLine.contains("sortedByDescending", ignoreCase = true)) {
            "BUG_LINE must mention sortedByDescending.\nOutput:\n$combined"
        }
        check(bugLine.contains("players.sortedByDescending", ignoreCase = true)) {
            "BUG_LINE must identify the exact buggy statement.\nOutput:\n$combined"
        }
        check(bugLine.contains("it.score", ignoreCase = true)) {
            "BUG_LINE must preserve the actual sortedByDescending selector (`it.score`).\nOutput:\n$combined"
        }

        // Agent must mention sortedByDescending in its analysis
        result.assertOutputContains("sortedByDescending", message = "agent must mention sortedByDescending")

        // Agent must identify the root cause: sortedByDescending returns a new list
        // but the return value is ignored
        val rootCause = findMarkerValue(combined, "ROOT_CAUSE")
        check(rootCause != null) {
            "Agent did not output required marker 'ROOT_CAUSE:'.\nOutput:\n$combined"
        }

        val ignoredReturnPatterns = listOf(
            "ignor", "unused", "discard", "return value", "not assigned", "not used"
        )
        val returnsNewListPatterns = listOf(
            "new list", "returns new", "does not modify", "doesn't modify",
            "not in place", "immutable", "original list", "original unsorted list",
        )

        val mentionsIgnoredReturn = ignoredReturnPatterns.any { pattern ->
            rootCause.contains(pattern, ignoreCase = true)
        }
        val mentionsNewListBehavior = returnsNewListPatterns.any { pattern ->
            rootCause.contains(pattern, ignoreCase = true)
        }
        check(mentionsIgnoredReturn && mentionsNewListBehavior) {
            "ROOT_CAUSE must explain that sortedByDescending returns a new list and its return value is ignored.\n" +
                    "Expected ignored patterns: $ignoredReturnPatterns\n" +
                    "Expected new-list patterns: $returnsNewListPatterns\nOutput:\n$combined"
        }
        check(!rootCause.contains("it.first", ignoreCase = true)) {
            "ROOT_CAUSE should not claim a selector bug (`it.first` vs `it.score`).\nOutput:\n$combined"
        }

        println("[TEST] Agent '$agentName' successfully identified the sortedByDescending bug")
    }

    private fun assertUsedExecuteCodeEvidence(combined: String) {
        val executionIdPattern = Regex("""\b(?:Execution ID|execution_id):\s*eid_[A-Za-z0-9_-]+""")
        val hasToolEvidence = executionIdPattern.containsMatchIn(combined)

        check(hasToolEvidence) {
            "Agent must show evidence of steroid_execute_code usage.\n" +
                    "Expected an execution id marker (`Execution ID: eid_...` or `execution_id: eid_...`).\nOutput:\n$combined"
        }
    }

    private fun findMarkerValue(output: String, marker: String): String? {
        val markerPrefix = "$marker:"
        return output.lineSequence()
            .firstOrNull { line -> line.trimStart().startsWith(markerPrefix, ignoreCase = true) }
            ?.substringAfter(':')
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
    }

    companion object {
        @JvmStatic
        val lifetime by lazy {
            CloseableStackHost()
        }

        val session by lazy {
            IdeContainer.create(
                lifetime,
                "ide-agent",
            )
        }

        @JvmStatic
        @BeforeAll
        fun beforeAll() {
            // Trigger session creation (IDE start, MCP readiness)
            // The aiAgents lazy property will also call waitForMcpReady()
            session.toString()
        }

        @JvmStatic
        @AfterAll
        fun tearDown() {
            lifetime.closeAllStacks()
        }
    }
}
