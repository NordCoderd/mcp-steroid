/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.integration.tests

import com.jonnyzzz.mcpSteroid.integration.infra.AiAgentDriver
import com.jonnyzzz.mcpSteroid.integration.infra.ConsoleDriver
import com.jonnyzzz.mcpSteroid.integration.infra.IdeDistribution
import com.jonnyzzz.mcpSteroid.integration.infra.IdeProduct
import com.jonnyzzz.mcpSteroid.integration.infra.IntelliJContainer
import com.jonnyzzz.mcpSteroid.integration.infra.create
import com.jonnyzzz.mcpSteroid.testHelper.AiAgentSession
import com.jonnyzzz.mcpSteroid.testHelper.CloseableStackHost
import com.jonnyzzz.mcpSteroid.testHelper.process.assertExitCode
import com.jonnyzzz.mcpSteroid.testHelper.process.assertOutputContains
import com.jonnyzzz.mcpSteroid.testHelper.titleCase
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.concurrent.TimeUnit
import kotlin.reflect.KProperty1

/**
 * Integration test: debugger demo for Rider (.NET).
 *
 * Runs AI agents inside a Docker container with Rider + MCP Steroid plugin.
 * The agent is asked to debug LeaderboardTests.cs and find the OrderByDescending bug
 * in Player.cs — the C# equivalent of the Kotlin sortedByDescending bug pattern.
 *
 * The bug: `players.OrderByDescending(p => p.Score)` returns a new ordered sequence
 * but the return value is ignored, so the original unsorted list is returned.
 *
 * Each test creates its own container for full isolation.
 */
class RiderDebuggerTest {
    private val lifetime by lazy {
        CloseableStackHost()
    }

    @AfterEach
    fun tearDown() {
        lifetime.closeAllStacks()
    }

    @Test
    @Timeout(value = 20, unit = TimeUnit.MINUTES)
    fun `claude debugs dotnet test in Rider via debugger`() = runRiderDebugDemo(AiAgentDriver::claude)

    private fun runRiderDebugDemo(agentName: KProperty1<AiAgentDriver, AiAgentSession>) {
        val session = IntelliJContainer.create(
            lifetime,
            consoleTitle = "Rider Debug with ${agentName.name.titleCase()}",
            distribution = IdeDistribution.Latest(IdeProduct.Rider),
        ).waitForProjectReady()
        val console = session.console

        val agent = session.aiAgents.run { agentName(this) }
        console.writeStep(1, "Building prompt for $agentName")

        val prompt = buildString {
            appendLine("# Task: Debug a failing NUnit test in a .NET project to find the bug")
            appendLine()
            appendLine("You MUST use the debugger to investigate why the test fails.")
            appendLine("Do NOT just read source code and guess -- the test validates debugger evidence.")
            appendLine()
            appendLine("## Instructions")
            appendLine()
            appendLine("1. The `LeaderboardTests.cs` file is already open in the editor. Read it to understand the test assertions.")
            appendLine("2. Find the corresponding `Player.cs` source file that contains the `Leaderboard.GetLeaderboard()` method")
            appendLine("3. Set a breakpoint inside `GetLeaderboard()` using `mcp-steroid://debugger/set-breakpoint`")
            appendLine("4. Debug the tests using Rider's native test runner:")
            appendLine("   - Open the test file in editor, position caret on the test class")
            appendLine("   - Fire `RiderUnitTestDebugContextAction` via ActionManager to start debugging")
            appendLine("   - Example pattern:")
            appendLine("   ```")
            appendLine("   val action = ActionManager.getInstance().getAction(\"RiderUnitTestDebugContextAction\")")
            appendLine("   val dataContext = DataManager.getInstance().getDataContext(editor.contentComponent)")
            appendLine("   val presentation = action.templatePresentation.clone()")
            appendLine("   val event = AnActionEvent.createEvent(dataContext, presentation, \"EditorPopup\", ActionUiKind.NONE, null)")
            appendLine("   ActionUtil.performAction(action, event)")
            appendLine("   ```")
            appendLine("5. Wait for breakpoint hit using `mcp-steroid://debugger/wait-for-suspend`")
            appendLine("6. Evaluate variables BEFORE and AFTER stepping over the OrderByDescending call")
            appendLine("7. Identify why the test assertion fails based on debugger evidence")
            appendLine()
            appendLine("Read `mcp-steroid://skill/debugger-skill` for debugger APIs.")
            appendLine("Read `mcp-steroid://skill/test-skill` for Rider test execution.")
            appendLine("Each links to resources with complete, copy-paste-ready code for each step.")
            appendLine()
            appendLine("IMPORTANT: This is a .NET/C# project opened in Rider.")
            appendLine("- Use `RiderUnitTestDebugContextAction` to debug tests (NOT JUnitConfiguration — that is Java-only)")
            appendLine("- Use `RiderUnitTestRunContextAction` to just run tests without debugging")
            appendLine("- Use XDebuggerManager, XDebuggerUtil for breakpoints and evaluation (these are cross-platform)")
            appendLine("- The solution is DemoRider.sln with projects DemoRider and DemoRider.Tests")
            appendLine("- Test results do NOT appear in RunContentManager/SMTRunnerConsoleView")
            appendLine()
            appendLine("## Required Output")
            appendLine()
            appendLine("Print these markers on separate lines:")
            appendLine("BUG_FOUND: yes")
            appendLine("BUG_LINE: <the exact buggy source line>")
            appendLine("ROOT_CAUSE: <must explain that OrderByDescending returns a new sequence AND that the return value is ignored/not assigned back>")
            appendLine("DEBUGGER_EVIDENCE: <BEFORE and AFTER values showing the issue, observed during test execution>")
            appendLine()
            appendLine("Also print BEFORE_VALUE and AFTER_VALUE markers when evaluating variables")
            appendLine("before and after the suspected buggy line executes.")
            appendLine()
            appendLine("## Rules")
            appendLine()
            appendLine("- You MUST use the debugger (set breakpoints inside the method under test, evaluate variables)")
            appendLine("- The debugging must occur in the context of running LeaderboardTests, not Program.cs")
            appendLine("- Do NOT use screenshots or UI input tools")
            appendLine("- Read MCP debugger resources for API patterns -- do not invent API calls")
        }

        console.writeStep(2, "Running agent prompt")

        val result = agent.runPrompt(prompt, timeoutSeconds = 600).awaitForProcessFinish()
        val output = result.stdout
        val combined = result.stdout + "\n" + result.stderr

        console.writeStep(3, "Validating agent output")

        val hasFinalMarkers = hasAnyMarkerLine(output, "BUG_FOUND", "Bug found") &&
                hasAnyMarkerLine(output, "ROOT_CAUSE", "Root cause")
        if (result.exitCode != 0 && !hasFinalMarkers) {
            console.writeError("Agent exited with code ${result.exitCode}")
            result.assertExitCode(0, message = "Rider debugger demo")
        }
        console.writeInfo("Agent exited with code ${result.exitCode ?: "?"}")

        console.writeInfo("Checking: steroid_execute_code usage evidence")
        assertUsedExecuteCodeEvidence(combined)
        console.writeSuccess("execute_code evidence found")

        console.writeInfo("Checking: BUG_LINE marker")
        val bugLine = findMarkerValue(output, "BUG_LINE", "Buggy line", "Bug line")
        check(bugLine != null) {
            "Agent did not output required marker 'BUG_LINE:' (or equivalent).\nOutput:\n$combined"
        }
        check(bugLine.contains("OrderByDescending", ignoreCase = true)) {
            "BUG_LINE must mention OrderByDescending.\nActual: $bugLine\nOutput:\n$combined"
        }
        console.writeSuccess("BUG_LINE: $bugLine")

        result.assertOutputContains("OrderByDescending", message = "agent must mention OrderByDescending")

        console.writeInfo("Checking: ROOT_CAUSE marker")
        assertRootCauseQuality(
            combined, output,
            firstAspectPatterns = listOf(
                "ignor", "unused", "discard", "return value", "not assigned", "not assigned back",
                "not used", "isn't assigned", "not stored", "not captured", "thrown away", "result is lost",
            ),
            secondAspectPatterns = listOf(
                "new sequence", "new collection", "returns new", "does not modify", "doesn't modify",
                "not in place", "original list", "new sorted", "sorted copy", "new ordered",
                "returns a new", "LINQ",
            ),
            explanation = "ROOT_CAUSE must explain that OrderByDescending returns a new sequence and its return value is ignored."
        )
        console.writeSuccess("ROOT_CAUSE quality validated")

        console.writeInfo("Checking: debugger evidence (suspension + evaluation)")
        assertDebuggerEvidence(combined, console)
        console.writeSuccess("Debugger evidence validated")

        console.writeSuccess("Agent '$agentName' identified the OrderByDescending bug via Rider debugging")
        console.writeHeader("PASSED")

        println("[TEST] Agent '$agentName' successfully debugged the failing .NET test in Rider")
    }

    private fun assertDebuggerEvidence(combined: String, console: ConsoleDriver) {
        val suspensionPatterns = listOf(
            Regex("""(?i)suspended at:\s*\S+:\d+"""),
            Regex("""(?i)breakpoint hit.*:\d+"""),
            Regex("""(?i)stopped at.*:\d+"""),
        )
        val hasSuspension = suspensionPatterns.any { it.containsMatchIn(combined) }
        if (hasSuspension) {
            console.writeSuccess("Found breakpoint suspension evidence")
        }

        val hasBeforeValue = combined.contains("BEFORE_VALUE:", ignoreCase = true)
        val hasAfterValue = combined.contains("AFTER_VALUE:", ignoreCase = true)
        if (hasBeforeValue) console.writeSuccess("Found BEFORE_VALUE evidence")
        if (hasAfterValue) console.writeSuccess("Found AFTER_VALUE evidence")

        val evaluationPatterns = listOf(
            Regex("""(?i)\b(players|result|scores|leaderboard)\s*=\s*\S+"""),
            Regex("""(?i)evaluating:"""),
            Regex("""(?i)result:\s*\S+"""),
            Regex("""(?i)after step:"""),
            Regex("""(?i)value:\s*\S+"""),
            Regex("""(?i)\b(players|result|scores)\.(Count|Length|Name|Score|First)"""),
        )
        val hasEvalEvidence = evaluationPatterns.any { it.containsMatchIn(combined) }
        if (hasEvalEvidence) {
            console.writeSuccess("Found variable evaluation evidence")
        }

        check(hasSuspension) {
            "Agent must show evidence of debugger suspension.\n" +
                    "Expected patterns:\n" +
                    "  - 'Debugger suspended at: <file>:<line>'\n" +
                    "  - 'Breakpoint hit at: <file>:<line>'\n" +
                    "  - 'Stopped at: <file>:<line>'\n" +
                    "This proves the debugger actually hit a breakpoint.\n" +
                    "Combined output length: ${combined.length} chars"
        }
        check(hasBeforeValue || hasAfterValue || hasEvalEvidence) {
            "Agent must show evidence of debugger expression evaluation.\n" +
                    "Expected evidence:\n" +
                    "  - BEFORE_VALUE/AFTER_VALUE markers\n" +
                    "  - Variable evaluation output (e.g., 'variable = value')\n" +
                    "  - Expression evaluation output (e.g., 'evaluating: expression', 'Result: value')\n" +
                    "  - Variable property access (e.g., 'players.Count', 'result.Name')\n" +
                    "This proves the agent evaluated expressions during debugging, not just read source code.\n" +
                    "Combined output length: ${combined.length} chars"
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

    private fun assertRootCauseQuality(
        combined: String,
        output: String,
        firstAspectPatterns: List<String>,
        secondAspectPatterns: List<String>,
        explanation: String,
    ) {
        val rootCause = findMarkerValue(output, "ROOT_CAUSE", "Root cause")
        check(rootCause != null) {
            "Agent did not output required marker 'ROOT_CAUSE:' (or equivalent).\nOutput:\n$combined"
        }

        val mentionsFirstAspect = firstAspectPatterns.any { pattern ->
            rootCause.contains(pattern, ignoreCase = true)
        }
        val mentionsSecondAspect = secondAspectPatterns.any { pattern ->
            rootCause.contains(pattern, ignoreCase = true)
        }
        check(mentionsFirstAspect && mentionsSecondAspect) {
            "$explanation\n" +
                    "Expected first-aspect patterns: $firstAspectPatterns\n" +
                    "Expected second-aspect patterns: $secondAspectPatterns\n" +
                    "Actual ROOT_CAUSE: $rootCause\nOutput:\n$combined"
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

        // Filter out template placeholders like <the exact buggy source line>
        // but allow legitimate code with < > (e.g., C# lambdas: p => p.Score)
        val templatePlaceholder = Regex("""<[a-zA-Z][^>]*>""")
        return candidates.lastOrNull { value ->
            val lowered = value.lowercase()
            !templatePlaceholder.containsMatchIn(value) &&
                    !lowered.contains("copy the") &&
                    !lowered.contains("one line description") &&
                    !lowered.contains("exact buggy source line")
        }
    }
}
