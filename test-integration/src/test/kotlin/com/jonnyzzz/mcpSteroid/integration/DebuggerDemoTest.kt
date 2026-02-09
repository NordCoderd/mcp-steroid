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
            appendLine("- First read MCP resource: mcp-steroid://debugger/overview via read_mcp_resource")
            appendLine("- Do NOT call list_mcp_resources/resources/list; do not enumerate all resources")
            appendLine("- First call steroid_list_projects and reuse that exact project_name")
            appendLine("- If any steroid_execute_code call returns 'Project not found', call steroid_list_projects again and switch to the returned project_name")
            appendLine("- Use short, stateful steroid_execute_code calls (no huge monolithic script)")
            appendLine("- Use at most 6 steroid_execute_code calls total")
            appendLine("- In the first successful source-inspection call, print the exact buggy source line text and reuse it verbatim later")
            appendLine("- If debugger setup fails with compiler/runtime errors, stop retrying broad scripts and do one final source-based diagnosis call that reads the exact source line from the file document")
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
            appendLine("Allowed MCP calls in this scenario:")
            appendLine("- read_mcp_resource (only for mcp-steroid://debugger/overview)")
            appendLine("- steroid_list_projects (project discovery)")
            appendLine("- steroid_execute_code (IDE actions)")
            appendLine("Do not call list_mcp_resources/resources/list.")
            appendLine("Do not use screenshots or UI input tools.")
            appendLine("After your first steroid_execute_code call, include this in your final response:")
            appendLine("TOOL_EVIDENCE: <copy the line starting with Execution ID: ...>")
            appendLine()
            appendLine("At the end of your analysis, output these markers on separate lines:")
            appendLine("BUG_FOUND: yes")
            appendLine("BUG_LINE: <copy the exact buggy source line>")
            appendLine("ROOT_CAUSE: <one line description>")
            appendLine("Do not use alternative labels like 'Buggy line' or 'Root cause'.")
        }

        val result = agent!!.runPrompt(prompt, timeoutSeconds = 600)
        val output = result.output
        val combined = output + "\n" + result.stderr

        // If CLI timed out but the agent already emitted required markers, keep validating the output.
        val hasFinalMarkers = hasAnyMarkerLine(output, "BUG_FOUND", "Bug found") &&
                hasAnyMarkerLine(output, "ROOT_CAUSE", "Root cause")
        if (result.exitCode != 0 && !hasFinalMarkers) {
            result.assertExitCode(0, message = "debugger demo")
        }

        // Agent must show evidence of MCP Steroid execute_code usage
        assertUsedExecuteCodeEvidence(combined)

        val bugLine = findMarkerValue(output, "BUG_LINE", "Buggy line", "Bug line")
        check(bugLine != null) {
            "Agent did not output required marker 'BUG_LINE:' (or equivalent).\nOutput:\n$combined"
        }
        check(bugLine.contains("sortedByDescending", ignoreCase = true)) {
            "BUG_LINE must mention sortedByDescending.\nOutput:\n$combined"
        }
        val hasExactBugStatement = bugLine.contains("players.sortedByDescending", ignoreCase = true) &&
                bugLine.contains("it.score", ignoreCase = true)
        val hasSortedLineEvidence = Regex("""(?im)sortedByDescending line\s*\(1-based\)\s*:\s*7""")
            .containsMatchIn(combined)
        check(hasExactBugStatement || hasSortedLineEvidence) {
            "BUG_LINE must identify the exact buggy statement, or execution logs must show line-number evidence " +
                    "for the sortedByDescending line.\nOutput:\n$combined"
        }

        // Agent must mention sortedByDescending in its analysis
        result.assertOutputContains("sortedByDescending", message = "agent must mention sortedByDescending")

        // Agent must identify the root cause: sortedByDescending returns a new list
        // but the return value is ignored
        val rootCause = findMarkerValue(output, "ROOT_CAUSE", "Root cause")
        check(rootCause != null) {
            "Agent did not output required marker 'ROOT_CAUSE:' (or equivalent).\nOutput:\n$combined"
        }

        val bugFound = findMarkerValue(output, "BUG_FOUND", "Bug found")
        val hasExplicitYes = bugFound?.equals("yes", ignoreCase = true) == true
        val inferredYes = bugFound == null && bugLine.isNotBlank() && rootCause.isNotBlank()
        check(hasExplicitYes || inferredYes) {
            "Agent did not confirm bug detection with 'BUG_FOUND: yes' and no valid fallback markers were found.\nOutput:\n$combined"
        }

        val ignoredReturnPatterns = listOf(
            "ignor", "unused", "discard", "return value", "not assigned", "not assigned back", "not used",
            "isn't assigned", "isn’t assigned", "ignored/not assigned",
        )
        val returnsNewListPatterns = listOf(
            "new list", "returns new", "does not modify", "doesn't modify",
            "not in place", "immutable", "original list", "original unsorted list",
            "new sorted list", "sorted copy",
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

    private fun hasAnyMarkerLine(output: String, vararg markers: String): Boolean {
        return markers.any { marker ->
            Regex("""(?im)^\s*[*_`>#-]*\s*${Regex.escape(marker)}\s*:""").containsMatchIn(output)
        }
    }

    private fun findMarkerValue(output: String, vararg markers: String): String? {
        if (markers.isEmpty()) return null
        val markerAlternation = markers.joinToString("|") { Regex.escape(it) }
        val markerRegex = Regex(
            pattern = """(?im)^\s*[*_`>#-]*\s*(?:$markerAlternation)\s*:\s*(.+?)\s*[*_`]*\s*$"""
        )
        val candidates = markerRegex.findAll(output).mapNotNull { match ->
            match.groupValues
                .getOrNull(1)
                ?.trim()
                ?.trim('*', '_', '`')
                ?.takeIf { it.isNotEmpty() }
        }.toList()

        return candidates.lastOrNull { value ->
            val lowered = value.lowercase()
            !value.contains('<') &&
                    !value.contains('>') &&
                    !lowered.contains("copy the") &&
                    !lowered.contains("one line description") &&
                    !lowered.contains("exact buggy source line")
        }
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
