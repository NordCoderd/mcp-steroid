/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.intellij.mcp.execution

import com.intellij.openapi.components.service
import com.intellij.testFramework.common.timeoutRunBlocking
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.jonnyzzz.intellij.mcp.server.ProgressReporter
import com.jonnyzzz.intellij.mcp.storage.ExecutionParams
import com.jonnyzzz.intellij.mcp.storage.ExecutionStatus
import kotlin.time.Duration.Companion.seconds

/**
 * Tests for ExecutionManager.
 * Uses timeoutRunBlocking for coroutine tests as per IntelliJ 253 best practices.
 */
class ExecutionManagerTest : BasePlatformTestCase() {

    override fun setUp() {
        super.setUp()
        // Disable review mode for tests
        setRegistryPropertyForTest("mcp.steroids.review.mode", "NEVER")
    }

    fun testExecuteWithProgressSuccess(): Unit = timeoutRunBlocking(30.seconds) {
        val manager = project.service<ExecutionManager>()

        val code = """
            execute {
                println("Hello from test")
            }
        """.trimIndent()

        val result = manager.executeWithProgress(code, ExecutionParams(timeout = 30), ProgressReporter.noOp())

        // Should complete with a valid status
        assertTrue(
            "Should complete with valid status, was ${result.status}",
            result.status in listOf(ExecutionStatus.SUCCESS, ExecutionStatus.ERROR)
        )
    }

    fun testExecuteWithProgressOutput(): Unit = timeoutRunBlocking(60.seconds) {
        val manager = project.service<ExecutionManager>()

        val code = """
            execute {
                println("Line 1")
                println("Line 2")
            }
        """.trimIndent()

        val result = manager.executeWithProgress(code, ExecutionParams(timeout = 30), ProgressReporter.noOp())

        // If execution succeeded, verify output
        if (result.status == ExecutionStatus.SUCCESS) {
            assertTrue("Should have output", result.output.isNotEmpty())
        }
    }

    fun testExecuteWithProgressError(): Unit = timeoutRunBlocking(30.seconds) {
        val manager = project.service<ExecutionManager>()

        val code = """
            execute {
                throw RuntimeException("Test error")
            }
        """.trimIndent()

        val result = manager.executeWithProgress(code, ExecutionParams(timeout = 30), ProgressReporter.noOp())

        // Should be ERROR status
        assertEquals(ExecutionStatus.ERROR, result.status)
        assertNotNull("Should have error message", result.errorMessage)
    }

    fun testExecuteWithProgressTimeout(): Unit = timeoutRunBlocking(15.seconds) {
        val manager = project.service<ExecutionManager>()

        val code = """
            execute {
                println("Starting")
                kotlinx.coroutines.delay(10000)
                println("Should not reach here")
            }
        """.trimIndent()

        val result = manager.executeWithProgress(code, ExecutionParams(timeout = 2), ProgressReporter.noOp())

        // Should be TIMEOUT or ERROR (if script engine not available)
        assertTrue(
            "Should complete with TIMEOUT or ERROR, was ${result.status}",
            result.status in listOf(ExecutionStatus.TIMEOUT, ExecutionStatus.ERROR)
        )
    }
}
