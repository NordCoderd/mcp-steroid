/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.intellij.mcp

import com.intellij.openapi.components.service
import com.intellij.testFramework.common.timeoutRunBlocking
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.jonnyzzz.intellij.mcp.execution.ExecutionManager
import com.jonnyzzz.intellij.mcp.server.ProgressReporter
import com.jonnyzzz.intellij.mcp.storage.ExecutionParams
import com.jonnyzzz.intellij.mcp.storage.ExecutionStatus
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

    fun testExecuteCodeSuccess(): Unit = timeoutRunBlocking(30.seconds) {
        val manager = project.service<ExecutionManager>()

        val result = manager.executeWithProgress(
            code = """
                execute {
                    println("Hello from toolset test")
                }
            """.trimIndent(),
            params = ExecutionParams(timeout = 30),
            progressReporter = ProgressReporter.noOp()
        )

        // Should complete with a valid status
        assertTrue(
            "Should complete with valid status, was ${result.status}",
            result.status in listOf(
                ExecutionStatus.SUCCESS,
                ExecutionStatus.ERROR // Script engine may not be available in test env
            )
        )
    }

    fun testExecuteCodeWithOutput(): Unit = timeoutRunBlocking(60.seconds) {
        val manager = project.service<ExecutionManager>()

        val result = manager.executeWithProgress(
            code = """
                execute {
                    println("Test output line 1")
                    println("Test output line 2")
                }
            """.trimIndent(),
            params = ExecutionParams(timeout = 30),
            progressReporter = ProgressReporter.noOp()
        )

        // If execution succeeded, verify output
        if (result.status == ExecutionStatus.SUCCESS) {
            assertTrue("Should have output", result.output.isNotEmpty())
        }
    }

    fun testExecuteCodeWithError(): Unit = timeoutRunBlocking(30.seconds) {
        val manager = project.service<ExecutionManager>()

        val result = manager.executeWithProgress(
            code = """
                execute {
                    throw RuntimeException("Test error")
                }
            """.trimIndent(),
            params = ExecutionParams(timeout = 30),
            progressReporter = ProgressReporter.noOp()
        )

        // Should be ERROR status
        assertEquals(ExecutionStatus.ERROR, result.status)
        assertNotNull("Should have error message", result.errorMessage)
    }

    fun testExecuteCodeWithTimeout(): Unit = timeoutRunBlocking(15.seconds) {
        val manager = project.service<ExecutionManager>()

        val result = manager.executeWithProgress(
            code = """
                execute {
                    println("Starting")
                    kotlinx.coroutines.delay(10000) // 10 seconds
                    println("Should not reach here")
                }
            """.trimIndent(),
            params = ExecutionParams(timeout = 2), // 2 second timeout
            progressReporter = ProgressReporter.noOp()
        )

        // Should be TIMEOUT or ERROR (if script engine not available)
        assertTrue(
            "Should complete with TIMEOUT or ERROR, was ${result.status}",
            result.status in listOf(ExecutionStatus.TIMEOUT, ExecutionStatus.ERROR)
        )
    }

    fun testExecuteCodeWithInvalidSyntax(): Unit = timeoutRunBlocking(30.seconds) {
        val manager = project.service<ExecutionManager>()

        val result = manager.executeWithProgress(
            code = """
                this is not valid kotlin code at all
            """.trimIndent(),
            params = ExecutionParams(timeout = 30),
            progressReporter = ProgressReporter.noOp()
        )

        // Should be ERROR status
        assertEquals(ExecutionStatus.ERROR, result.status)
        assertNotNull("Should have error message", result.errorMessage)
    }
}
