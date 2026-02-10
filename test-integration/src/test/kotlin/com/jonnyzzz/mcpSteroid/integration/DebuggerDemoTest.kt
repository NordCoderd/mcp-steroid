/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.integration

import com.jonnyzzz.mcpSteroid.testHelper.AiAgentSession
import com.jonnyzzz.mcpSteroid.testHelper.CloseableStackHost
import com.jonnyzzz.mcpSteroid.testHelper.assertExitCode
import com.jonnyzzz.mcpSteroid.testHelper.assertOutputContains
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.concurrent.TimeUnit

/**
 * Integration test: debugger demo from ai-tests/07-debugger.md.
 *
 * Runs AI agents inside a Docker container with IntelliJ IDEA + MCP Steroid plugin.
 * The agent is asked to debug DemoByJonnyzzz.kt and find the sortedByDescending bug.
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
        val session = IdeContainer.create(
            lifetime, "ide-agent",
            runId = "debugger-$agentName",
            consoleTitle = "Debugger Demo ($agentName)",
            waitForProjectReady = true,
        )
        val console = session.console

        val agent: AiAgentSession? = session.aiAgentDriver.aiAgents[agentName]
        assumeTrue(agent != null, "Agent '$agentName' is not configured")
        console.writeStep(1, "Building prompt for $agentName")

        val prompt = buildString {
            appendLine("# Task: Debug DemoByJonnyzzz.kt to find the bug")
            appendLine()
            appendLine("You MUST use the IntelliJ debugger to evaluate variables at a breakpoint.")
            appendLine("Do NOT just read source code and guess -- the test validates debugger evidence.")
            appendLine()
            appendLine("## STEP 1 — Discover project + find file")
            appendLine()
            appendLine("Call steroid_list_projects first, then use steroid_execute_code:")
            appendLine("- Use readAction { FilenameIndex.getVirtualFilesByName(\"DemoByJonnyzzz.kt\", project, com.intellij.psi.search.GlobalSearchScope.projectScope(project)) } to find the file")
            appendLine("- Read file content via FileDocumentManager.getInstance().getDocument(file)!!.text")
            appendLine("- Find the println line number (0-indexed) -- this is the breakpoint target")
            appendLine("- Find the sortedByDescending line number (0-indexed) -- this is the bug")
            appendLine("- Print both line numbers and their text")
            appendLine()
            appendLine("## STEP 2 — Set breakpoint on println line")
            appendLine()
            appendLine("Set breakpoint on the println(...) line (NOT the sortedByDescending line, which is inline and unreliable):")
            appendLine("```kotlin")
            appendLine("import com.intellij.xdebugger.XDebuggerUtil")
            appendLine("import kotlinx.coroutines.Dispatchers")
            appendLine("withContext(Dispatchers.EDT) {")
            appendLine("    XDebuggerUtil.getInstance().toggleLineBreakpoint(project, file, PRINTLN_LINE_INDEX)")
            appendLine("}")
            appendLine("```")
            appendLine()
            appendLine("## STEP 3 — Create run config + start debugger + wait for suspend (ALL IN ONE CALL)")
            appendLine()
            appendLine("CRITICAL: Combine ALL of these in a SINGLE steroid_execute_code call:")
            appendLine("```kotlin")
            appendLine("import com.intellij.execution.ProgramRunnerUtil")
            appendLine("import com.intellij.execution.application.ApplicationConfiguration")
            appendLine("import com.intellij.execution.application.ApplicationConfigurationType")
            appendLine("import com.intellij.execution.executors.DefaultDebugExecutor")
            appendLine("import com.intellij.execution.RunManager")
            appendLine("import com.intellij.xdebugger.XDebuggerManager")
            appendLine("import kotlinx.coroutines.Dispatchers")
            appendLine()
            appendLine("val runManager = RunManager.getInstance(project)")
            appendLine("val factory = ApplicationConfigurationType.getInstance().configurationFactories[0]")
            appendLine("val settings = runManager.createConfiguration(\"DebugDemo\", factory)")
            appendLine("val config = settings.configuration as ApplicationConfiguration")
            appendLine("config.mainClassName = \"com.jonnyzzz.mcpSteroid.demo.DemoByJonnyzzzKt\"")
            appendLine("// IMPORTANT: select the *.main module, not the root module")
            appendLine("config.setModule(project.modules.find { it.name.endsWith(\".main\") } ?: project.modules.first())")
            appendLine("settings.storeInLocalWorkspace()")
            appendLine("runManager.addConfiguration(settings)")
            appendLine()
            appendLine("withContext(Dispatchers.EDT) {")
            appendLine("    ProgramRunnerUtil.executeConfiguration(settings, DefaultDebugExecutor.getDebugExecutorInstance())")
            appendLine("}")
            appendLine("println(\"Debug started, polling for suspension...\")")
            appendLine()
            appendLine("val dm = XDebuggerManager.getInstance(project)")
            appendLine("var suspended = false")
            appendLine("repeat(60) { attempt ->")
            appendLine("    val s = dm.currentSession")
            appendLine("    if (s != null && s.isSuspended) {")
            appendLine("        val pos = s.currentStackFrame?.sourcePosition")
            appendLine("        println(\"Suspended at: \${pos?.file?.name}:\${(pos?.line ?: -1) + 1}\")")
            appendLine("        suspended = true")
            appendLine("        return")
            appendLine("    }")
            appendLine("    delay(500)")
            appendLine("}")
            appendLine("if (!suspended) error(\"Debugger did not suspend after 30s\")")
            appendLine("```")
            appendLine()
            appendLine("## STEP 4 — Step over println + evaluate BEFORE")
            appendLine()
            appendLine("Step over (to move from println to the sortedByDescending line), then evaluate `players`.")
            appendLine("Use this EXACT evaluateExpression helper (do NOT write your own):")
            appendLine("```kotlin")
            appendLine("import com.intellij.xdebugger.XDebuggerManager")
            appendLine("import com.intellij.xdebugger.evaluation.XDebuggerEvaluator")
            appendLine("import com.intellij.xdebugger.frame.XFullValueEvaluator")
            appendLine("import com.intellij.xdebugger.frame.XValue")
            appendLine("import com.intellij.xdebugger.frame.XValueNode")
            appendLine("import com.intellij.xdebugger.frame.XValuePlace")
            appendLine("import com.intellij.xdebugger.frame.presentation.XValuePresentation")
            appendLine("import com.intellij.xdebugger.impl.breakpoints.XExpressionImpl")
            appendLine("import com.intellij.xdebugger.impl.ui.tree.nodes.XValuePresentationUtil")
            appendLine("import kotlinx.coroutines.CompletableDeferred")
            appendLine("import kotlinx.coroutines.Dispatchers")
            appendLine("import kotlinx.coroutines.future.await")
            appendLine("import kotlinx.coroutines.withTimeout")
            appendLine("import javax.swing.Icon")
            appendLine("import kotlin.time.Duration.Companion.seconds")
            appendLine()
            appendLine("suspend fun evaluateExpression(evaluator: XDebuggerEvaluator, expr: String): String {")
            appendLine("    val valueDeferred = CompletableDeferred<XValue>()")
            appendLine("    evaluator.evaluate(XExpressionImpl.fromText(expr), object : XDebuggerEvaluator.XEvaluationCallback {")
            appendLine("        override fun evaluated(value: XValue) { valueDeferred.complete(value) }")
            appendLine("        override fun errorOccurred(msg: String) { valueDeferred.completeExceptionally(Exception(msg)) }")
            appendLine("    }, null)")
            appendLine("    val value = withTimeout(30.seconds) { valueDeferred.await() }")
            appendLine("    // Wait for value descriptor to be fully initialized (prevents 'Collecting data...' race)")
            appendLine("    withTimeout(30.seconds) { value.isReady.await() }")
            appendLine("    val presentationDeferred = CompletableDeferred<String>()")
            appendLine("    value.computePresentation(object : XValueNode {")
            appendLine("        override fun setPresentation(icon: Icon?, type: String?, text: String, hasChildren: Boolean) { presentationDeferred.complete(text) }")
            appendLine("        override fun setPresentation(icon: Icon?, pres: XValuePresentation, hasChildren: Boolean) { presentationDeferred.complete(XValuePresentationUtil.computeValueText(pres)) }")
            appendLine("        override fun setFullValueEvaluator(e: XFullValueEvaluator) {}")
            appendLine("        override fun isObsolete() = false")
            appendLine("    }, XValuePlace.TOOLTIP)")
            appendLine("    return withTimeout(30.seconds) { presentationDeferred.await() }")
            appendLine("}")
            appendLine()
            appendLine("// Step over the println line")
            appendLine("val session = XDebuggerManager.getInstance(project).currentSession!!")
            appendLine("withContext(Dispatchers.EDT) { session.stepOver(false) }")
            appendLine("repeat(20) {")
            appendLine("    if (session.isSuspended) {")
            appendLine("        println(\"After step: \${session.currentStackFrame?.sourcePosition?.file?.name}:\${(session.currentStackFrame?.sourcePosition?.line ?: -1) + 1}\")")
            appendLine("        // Evaluate BEFORE sortedByDescending executes")
            appendLine("        val frame = session.currentStackFrame!!")
            appendLine("        val evaluator = frame.evaluator!!")
            appendLine("        val result = evaluateExpression(evaluator, \"players.toString()\")")
            appendLine("        println(\"BEFORE_VALUE: \$result\")")
            appendLine("        return")
            appendLine("    }")
            appendLine("    delay(250)")
            appendLine("}")
            appendLine("```")
            appendLine()
            appendLine("## STEP 5 — Step over sortedByDescending + evaluate AFTER")
            appendLine()
            appendLine("```kotlin")
            appendLine("// (same imports and evaluateExpression helper as STEP 4)")
            appendLine("val session = XDebuggerManager.getInstance(project).currentSession!!")
            appendLine("withContext(Dispatchers.EDT) { session.stepOver(false) }")
            appendLine("repeat(20) {")
            appendLine("    if (session.isSuspended) {")
            appendLine("        println(\"After step: \${session.currentStackFrame?.sourcePosition?.file?.name}:\${(session.currentStackFrame?.sourcePosition?.line ?: -1) + 1}\")")
            appendLine("        val frame = session.currentStackFrame!!")
            appendLine("        val evaluator = frame.evaluator!!")
            appendLine("        val result = evaluateExpression(evaluator, \"players.toString()\")")
            appendLine("        println(\"AFTER_VALUE: \$result\")")
            appendLine("        return")
            appendLine("    }")
            appendLine("    delay(250)")
            appendLine("}")
            appendLine("```")
            appendLine()
            appendLine("## STEP 6 — Output conclusions")
            appendLine()
            appendLine("Print these markers on separate lines:")
            appendLine("BUG_FOUND: yes")
            appendLine("BUG_LINE: <the exact source line, e.g. players.sortedByDescending { it.score }>")
            appendLine("ROOT_CAUSE: <must mention that sortedByDescending returns a new list AND the return value is ignored/unused>")
            appendLine("DEBUGGER_EVIDENCE: <the BEFORE and AFTER values showing the list was unchanged>")
            appendLine()
            appendLine("## Rules")
            appendLine("- Do NOT call list_mcp_resources or resources/list")
            appendLine("- Do NOT write your own XEvaluationCallback — use the evaluateExpression() above")
            appendLine("- Do NOT use screenshots or UI input tools")
            appendLine("- If 'Project not found', call steroid_list_projects again")
            appendLine("- Use at most 10 steroid_execute_code calls total")
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
     * 1. Suspension evidence — the debugger hit a breakpoint ("Suspended at:")
     * 2. Evaluation evidence — the agent evaluated expressions at a breakpoint
     *    (BEFORE_VALUE or AFTER_VALUE markers, or evaluateExpression / players.toString output)
     */
    private fun assertDebuggerEvidence(combined: String, console: ConsoleDriver) {
        // Check for breakpoint suspension evidence
        val suspendedPattern = Regex("""Suspended at:\s*\S+:\d+""")
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
            "Agent must show evidence of debugger suspension (expected 'Suspended at: <file>:<line>').\n" +
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
