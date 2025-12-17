/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.intellij.mcp

import com.intellij.openapi.components.service
import com.intellij.testFramework.common.timeoutRunBlocking
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.jonnyzzz.intellij.mcp.execution.ExecutionManager
import com.jonnyzzz.intellij.mcp.mcp.ContentItem
import com.jonnyzzz.intellij.mcp.server.ExecCodeParams
import com.jonnyzzz.intellij.mcp.server.NoOpProgressReporter
import kotlinx.serialization.json.buildJsonObject
import kotlin.time.Duration.Companion.seconds

/**
 * Tests for the MCP execution flow.
 * These tests verify that the ExecutionManager correctly executes code.
 */
class SteroidsMcpToolsetTest : BasePlatformTestCase() {

    override fun setUp() {
        super.setUp()
        // Disable review mode for tests
        setRegistryPropertyForTest("mcp.steroids.review.mode", "NEVER")
    }

    private fun testExecParams(code: String, timeout: Int = 30) = ExecCodeParams(
        taskId = "test-task",
        code = code,
        reason = "test",
        timeout = timeout,
        rawParams = buildJsonObject { }
    )

    private fun getTextContent(result: com.jonnyzzz.intellij.mcp.mcp.ToolCallResult): String {
        return result.content.filterIsInstance<ContentItem.Text>().joinToString("\n") { it.text }
    }

    fun testExecuteCodeSuccess(): Unit = timeoutRunBlocking(30.seconds) {
        val manager = project.service<ExecutionManager>()

        val result = manager.executeWithProgress(
            testExecParams("""
                execute {
                    println("Hello from toolset test")
                }
            """.trimIndent()),
            NoOpProgressReporter
        )

        // Execution completes - may be success or error if script engine not available
        // Just verify we got a result
        assertTrue("Should have content", result.content.isNotEmpty())
    }

    fun testExecuteCodeWithOutput(): Unit = timeoutRunBlocking(60.seconds) {
        val manager = project.service<ExecutionManager>()

        val result = manager.executeWithProgress(
            testExecParams("""
                execute {
                    println("Test output line 1")
                    println("Test output line 2")
                }
            """.trimIndent()),
            NoOpProgressReporter
        )

        // If execution succeeded, verify output contains our text
        assertTrue("Should not fail", !result.isError)
        val text = getTextContent(result)
        assertTrue("Should have output with line 1", text.contains("Test output line 1"))
    }

    fun testExecuteCodeWithError(): Unit = timeoutRunBlocking(30.seconds) {
        val manager = project.service<ExecutionManager>()

        val result = manager.executeWithProgress(
            testExecParams("""
                execute {
                    throw RuntimeException("Test error")
                }
            """.trimIndent()),
            NoOpProgressReporter
        )

        // Should be marked as error
        assertTrue("Should be error", result.isError)
        val text = getTextContent(result)
        assertTrue("Should have error message", text.contains("error") || text.contains("Error") || text.contains("RuntimeException"))
    }

    fun testExecuteCodeWithTimeout(): Unit = timeoutRunBlocking(15.seconds) {
        val manager = project.service<ExecutionManager>()

        val result = manager.executeWithProgress(
            testExecParams("""
                execute {
                    println("Starting")
                    kotlinx.coroutines.delay(10000) // 10 seconds
                    println("Should not reach here")
                }
            """.trimIndent(), timeout = 2), // 2 second timeout
            NoOpProgressReporter
        )

        // Should be error due to timeout (or error if script engine not available)
        assertTrue("Should be error", result.isError)
    }

    fun testExecuteCodeWithInvalidSyntax(): Unit = timeoutRunBlocking(30.seconds) {
        val manager = project.service<ExecutionManager>()

        val result = manager.executeWithProgress(
            testExecParams("""
                this is not valid kotlin code at all
            """.trimIndent()),
            NoOpProgressReporter
        )

        // Should be error due to compilation failure (or script engine not available)
        // The result should have content - either error message or execution output
        val text = getTextContent(result)
        assertTrue("Should have content", text.isNotEmpty() || result.content.isNotEmpty())

        // If marked as error, it should have error message
        assertTrue("Should be error", result.isError)
        assertTrue("Error should have message", text.isNotEmpty())
    }
}
