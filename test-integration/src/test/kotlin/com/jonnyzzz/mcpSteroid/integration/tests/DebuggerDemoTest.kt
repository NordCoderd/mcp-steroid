/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.integration.tests

import com.jonnyzzz.mcpSteroid.integration.infra.ConsoleDriver
import com.jonnyzzz.mcpSteroid.integration.infra.IntelliJContainer
import com.jonnyzzz.mcpSteroid.integration.infra.create
import com.jonnyzzz.mcpSteroid.testHelper.AiAgentSession
import com.jonnyzzz.mcpSteroid.testHelper.CloseableStackHost
import com.jonnyzzz.mcpSteroid.testHelper.assertExitCode
import com.jonnyzzz.mcpSteroid.testHelper.assertOutputContains
import com.jonnyzzz.mcpSteroid.testHelper.titleCase
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.concurrent.TimeUnit

/**
 * Integration test: debugger demo from ai-tests/07-debugger.md.
 *
 * Runs AI agents inside a Docker container with IntelliJ IDEA + MCP Steroid plugin.
 * The agent is asked to debug DemoByJonnyzzz.kt and find the sortedByDescending bug.
 *
 * The prompt intentionally avoids giving the agent any IntelliJ API code. Instead,
 * it directs the agent to read MCP debugger resources (mcp-steroid://debugger/*)
 * which contain complete, copy-paste-ready code for each step. This tests whether
 * agents can discover and use MCP resources independently.
 *
 * Each test creates its own IdeContainer for full isolation.
 * The container is kept alive after the test for debugging (removed on next run).
 * Video is always recorded and mounted to the host for live preview.
 */
class DebuggerDemoTest {
    private val lifetime by lazy {
        CloseableStackHost()
    }

    @AfterEach
    fun tearDown() {
        lifetime.closeAllStacks()
    }

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
        val session = IntelliJContainer.create(
            lifetime, "ide-agent",
            consoleTitle = "Debugger with ${agentName.titleCase()}",
        ).waitForProjectReady()
        val console = session.console

        val agent: AiAgentSession? = session.aiAgents.aiAgents[agentName]
        Assumptions.assumeTrue(agent != null, "Agent '$agentName' is not configured")
        console.writeStep(1, "Building prompt for $agentName")

        val prompt = buildString {
            appendLine("# Task: Debug DemoByJonnyzzz.kt to find the bug")
            appendLine()
            appendLine("You MUST use the IntelliJ debugger to investigate the bug.")
            appendLine("Do NOT just read source code and guess -- the test validates debugger evidence.")
            appendLine()
            appendLine("## Instructions")
            appendLine()
            appendLine("1. Find `DemoByJonnyzzz.kt` in the project and read it")
            appendLine("2. Use the debugger to set a breakpoint, run the program, and evaluate variables")
            appendLine("3. Step through the code and observe how variables change before and after key lines")
            appendLine("4. Identify the bug based on debugger evidence")
            appendLine()
            appendLine("Read `mcp-steroid://skill/debugger-skill` to learn how to use the debugger APIs.")
            appendLine("It links to individual resources with complete, copy-paste-ready code for each step.")
            appendLine()
            appendLine("## Required Output")
            appendLine()
            appendLine("Print these markers on separate lines:")
            appendLine("BUG_FOUND: yes")
            appendLine("BUG_LINE: <the exact buggy source line>")
            appendLine("ROOT_CAUSE: <must explain what the bug is and why, mentioning both that sortedByDescending returns a new list AND that the return value is ignored/unused>")
            appendLine("DEBUGGER_EVIDENCE: <BEFORE and AFTER values showing the issue>")
            appendLine()
            appendLine("Also print BEFORE_VALUE and AFTER_VALUE markers when evaluating variables")
            appendLine("before and after the suspected buggy line executes.")
            appendLine()
            appendLine("## Rules")
            appendLine()
            appendLine("- You MUST use the debugger (set breakpoints, evaluate variables, step through code)")
            appendLine("- Do NOT use screenshots or UI input tools")
            appendLine("- Read MCP debugger resources for API patterns -- do not invent API calls")
        }

        console.writeStep(2, "Running agent prompt")

        val result = agent!!.runPrompt(prompt, timeoutSeconds = 600)
        val output = result.output
        // Use rawOutput for evidence checks: Claude's stream-json mode puts
        // execution IDs in NDJSON tool_result events, not in the final extracted text.
        val combined = result.rawOutput + "\n" + result.stderr

        console.writeStep(3, "Validating agent output")

        // If CLI timed out but the agent already emitted required markers, keep validating the output.
        val hasFinalMarkers = hasAnyMarkerLine(output, "BUG_FOUND", "Bug found") &&
                hasAnyMarkerLine(output, "ROOT_CAUSE", "Root cause")
        if (result.exitCode != 0 && !hasFinalMarkers) {
            console.writeError("Agent exited with code ${result.exitCode}")
            result.assertExitCode(0, message = "debugger demo")
        }
        console.writeInfo("Agent exited with code ${result.exitCode ?: "?"}")

        // Agent must show evidence of MCP Steroid execute_code usage
        console.writeInfo("Checking: steroid_execute_code usage evidence")
        assertUsedExecuteCodeEvidence(combined)
        console.writeSuccess("execute_code evidence found")

        console.writeInfo("Checking: BUG_LINE marker")
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
        console.writeSuccess("BUG_LINE: $bugLine")

        // Agent must mention sortedByDescending in its analysis
        result.assertOutputContains("sortedByDescending", message = "agent must mention sortedByDescending")

        // Agent must identify the root cause: sortedByDescending returns a new list
        // but the return value is ignored
        console.writeInfo("Checking: ROOT_CAUSE marker")
        val rootCause = findMarkerValue(output, "ROOT_CAUSE", "Root cause")
        check(rootCause != null) {
            "Agent did not output required marker 'ROOT_CAUSE:' (or equivalent).\nOutput:\n$combined"
        }
        console.writeSuccess("ROOT_CAUSE: $rootCause")

        console.writeInfo("Checking: BUG_FOUND marker")
        val bugFound = findMarkerValue(output, "BUG_FOUND", "Bug found")
        val hasExplicitYes = bugFound?.equals("yes", ignoreCase = true) == true
        val inferredYes = bugFound == null && bugLine.isNotBlank() && rootCause.isNotBlank()
        check(hasExplicitYes || inferredYes) {
            "Agent did not confirm bug detection with 'BUG_FOUND: yes' and no valid fallback markers were found.\nOutput:\n$combined"
        }
        console.writeSuccess("BUG_FOUND: ${bugFound ?: "(inferred)"}")

        console.writeInfo("Checking: ROOT_CAUSE quality")
        val ignoredReturnPatterns = listOf(
            "ignor", "unused", "discard", "return value", "not assigned", "not assigned back", "not used",
            "isn't assigned", "ignored/not assigned", "not stored", "not captured", "thrown away", "result is lost",
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
        console.writeSuccess("ROOT_CAUSE quality validated")

        // Validate debugger evidence: the agent must have actually used the debugger,
        // not just read source code and guessed the answer.
        console.writeInfo("Checking: debugger evidence (suspension + evaluation)")
        assertDebuggerEvidence(combined, console)
        console.writeSuccess("Debugger evidence validated")

        console.writeSuccess("Agent '$agentName' identified the sortedByDescending bug")
        console.writeHeader("PASSED")

        println("[TEST] Agent '$agentName' successfully identified the sortedByDescending bug")
    }

    /**
     * Validates that the agent actually used the debugger (not just read source code).
     * Checks for:
     * 1. Suspension evidence -- the debugger hit a breakpoint ("suspended at:")
     * 2. Evaluation evidence -- the agent evaluated expressions at a breakpoint
     *    (BEFORE_VALUE or AFTER_VALUE markers, or evaluateExpression / players.toString output)
     */
    private fun assertDebuggerEvidence(combined: String, console: ConsoleDriver) {
        // Check for breakpoint suspension evidence (case-insensitive to match both
        // "Suspended at:" from custom code and "Debugger suspended at:" from MCP resources)
        val suspendedPattern = Regex("""(?i)suspended at:\s*\S+:\d+""")
        val hasSuspension = suspendedPattern.containsMatchIn(combined)
        if (hasSuspension) {
            console.writeSuccess("Found breakpoint suspension evidence")
        }
        // Check for debugger evaluation evidence (BEFORE_VALUE / AFTER_VALUE from step+eval)
        val hasBeforeValue = combined.contains("BEFORE_VALUE:", ignoreCase = true)
        val hasAfterValue = combined.contains("AFTER_VALUE:", ignoreCase = true)
        if (hasBeforeValue) console.writeSuccess("Found BEFORE_VALUE evidence")
        if (hasAfterValue) console.writeSuccess("Found AFTER_VALUE evidence")

        // Broader evaluation evidence: any expression evaluation output from the debugger
        val evaluationPatterns = listOf(
            "players =", "players.size =", "sorted result =",  // from evaluate-expression.kts
            "players.toString()",  // the expression we ask the agent to evaluate
            "After step:",  // from step-over
        )
        val hasEvalEvidence = evaluationPatterns.any { combined.contains(it, ignoreCase = true) }

        // Must have suspension evidence + at least some evaluation evidence
        check(hasSuspension) {
            "Agent must show evidence of debugger suspension (expected 'Debugger suspended at: <file>:<line>' or similar).\n" +
                    "This proves the debugger actually hit a breakpoint.\nOutput:\n$combined"
        }
        check(hasBeforeValue || hasAfterValue || hasEvalEvidence) {
            "Agent must show evidence of debugger expression evaluation.\n" +
                    "Expected BEFORE_VALUE/AFTER_VALUE markers or expression evaluation output.\nOutput:\n$combined"
        }
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
}
