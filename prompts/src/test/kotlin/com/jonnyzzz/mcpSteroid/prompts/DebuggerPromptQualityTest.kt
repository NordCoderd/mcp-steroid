/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.prompts

import com.jonnyzzz.mcpSteroid.prompts.generated.debugger.DebuggerIndex
import com.jonnyzzz.mcpSteroid.prompts.generated.skill.SkillIndex
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DebuggerPromptQualityTest {
    private val debuggerIndex = DebuggerIndex()
    private val skillIndex = SkillIndex()

    @Test
    fun testSetLineBreakpointUsesToggleLineBreakpoint() {
        val prompt = debuggerIndex.setLineBreakpointMd.ktBlock000.readPrompt()
        assertTrue(prompt.contains("toggleLineBreakpoint")) { "Expected toggleLineBreakpoint guidance" }
        assertTrue(prompt.contains("Dispatchers.EDT")) { "Expected EDT guidance" }
        assertFalse(
            prompt.contains("breakpointManager.addLineBreakpoint(")
        ) { "Should not use addLineBreakpoint(...) directly in code" }
    }

    @Test
    fun testDebugRunConfigurationUsesModernProgramRunnerUtilPackage() {
        val prompt = debuggerIndex.debugRunConfigurationMd.ktBlock000.readPrompt()
        assertTrue(
            prompt.contains("import com.intellij.execution.ProgramRunnerUtil")
        ) { "Expected modern ProgramRunnerUtil package" }
        assertFalse(
            prompt.contains("import com.intellij.execution.runners.ProgramRunnerUtil")
        ) { "Should not use outdated ProgramRunnerUtil package" }
    }

    @Test
    fun testDebuggerOverviewClarifiesZeroBasedLineNumbers() {
        val prompt = debuggerIndex.overviewMd.payload.readPrompt()
        assertTrue(
            prompt.contains("0-indexed", ignoreCase = true)
        ) { "Expected explicit 0-indexed line guidance" }
    }

    @Test
    fun testDebuggerSkillDocumentsCriticalImports() {
        val prompt = skillIndex.debuggerSkillMd.payload.readPrompt()
        assertTrue(
            prompt.contains("kotlinx.coroutines.suspendCancellableCoroutine")
        ) { "Expected suspendCancellableCoroutine import guidance" }
        assertTrue(
            prompt.contains("com.intellij.execution.ProgramRunnerUtil")
        ) { "Expected ProgramRunnerUtil package guidance" }
        assertTrue(
            prompt.contains("not `com.intellij.execution.runners.ProgramRunnerUtil`")
        ) { "Expected explicit anti-pattern warning for old ProgramRunnerUtil package" }
    }
}
