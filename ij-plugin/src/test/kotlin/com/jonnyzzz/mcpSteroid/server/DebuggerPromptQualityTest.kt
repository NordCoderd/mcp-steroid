/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.server

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.jonnyzzz.mcpSteroid.prompts.generated.debugger.DebuggerIndex
import com.jonnyzzz.mcpSteroid.prompts.generated.skill.SkillIndex

class DebuggerPromptQualityTest : BasePlatformTestCase() {
    private val debuggerIndex = DebuggerIndex()
    private val skillIndex = SkillIndex()

    override fun runInDispatchThread(): Boolean = false

    fun testSetLineBreakpointUsesToggleLineBreakpoint() {
        val prompt = debuggerIndex.setLineBreakpointKts.payload.readPrompt()
        assertTrue("Expected toggleLineBreakpoint guidance", prompt.contains("toggleLineBreakpoint"))
        assertTrue("Expected EDT guidance", prompt.contains("Dispatchers.EDT"))
        assertFalse(
            "Should not use addLineBreakpoint(...) directly in code",
            prompt.contains("breakpointManager.addLineBreakpoint(")
        )
    }

    fun testDebugRunConfigurationUsesModernProgramRunnerUtilPackage() {
        val prompt = debuggerIndex.debugRunConfigurationKts.payload.readPrompt()
        assertTrue(
            "Expected modern ProgramRunnerUtil package",
            prompt.contains("import com.intellij.execution.ProgramRunnerUtil")
        )
        assertFalse(
            "Should not use outdated ProgramRunnerUtil package",
            prompt.contains("import com.intellij.execution.runners.ProgramRunnerUtil")
        )
    }

    fun testDebuggerOverviewClarifiesZeroBasedLineNumbers() {
        val prompt = debuggerIndex.overviewMd.payload.readPrompt()
        assertTrue(
            "Expected explicit 0-indexed line guidance",
            prompt.contains("0-indexed", ignoreCase = true)
        )
    }

    fun testDebuggerSkillDocumentsCriticalImports() {
        val prompt = skillIndex.debuggerSkillMd.payload.readPrompt()
        assertTrue(
            "Expected suspendCancellableCoroutine import guidance",
            prompt.contains("kotlinx.coroutines.suspendCancellableCoroutine")
        )
        assertTrue(
            "Expected ProgramRunnerUtil package guidance",
            prompt.contains("com.intellij.execution.ProgramRunnerUtil")
        )
        assertTrue(
            "Expected explicit anti-pattern warning for old ProgramRunnerUtil package",
            prompt.contains("not `com.intellij.execution.runners.ProgramRunnerUtil`")
        )
    }
}
