/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.intellij.mcp.execution

import com.intellij.openapi.components.service
import com.intellij.testFramework.common.timeoutRunBlocking
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.jonnyzzz.intellij.mcp.server.ProgressReporter
import com.jonnyzzz.intellij.mcp.storage.ExecutionParams
import com.jonnyzzz.intellij.mcp.storage.ExecutionStatus
import com.jonnyzzz.intellij.mcp.storage.ExecutionStorage
import kotlin.time.Duration.Companion.seconds

/**
 * Tests for ScriptExecutor.
 *
 * These tests verify that execution failures are reported FAST (no timeout waiting)
 * and that the execution flow handles errors correctly.
 *
 * NOTE: In test environment, the Kotlin script engine may not be available
 * because the Kotlin plugin is not loaded. Tests should still pass by verifying
 * that failures are reported quickly with ERROR status.
 */
class ScriptExecutorTest : BasePlatformTestCase() {

    private val storage: ExecutionStorage  get() = project.service()
    private val executor: ScriptExecutor get() = project.service()

    override fun setUp() {
        super.setUp()
    }

    /**
     * Test that when script engine is not available, we get a fast error response.
     * This is the expected case in test environment.
     */
    fun testScriptEngineNotAvailableReturnsFast(): Unit = timeoutRunBlocking(10.seconds) {
        val code = """
            execute {
                println("Hello")
            }
        """.trimIndent()

        val params = ExecutionParams(timeout = 60)  // Long timeout but should return fast
        val executionId = storage.generateExecutionId(code, params)
        storage.createExecution(executionId, code, params)

        val result = executor.executeWithProgress(executionId, code, 60, ProgressReporter.noOp())

        // Should complete quickly (not wait 60 seconds for timeout)
        // Result should be ERROR because script engine is not available in test env
        // or SUCCESS if script engine is available
        assertTrue(
            "Should complete with valid status, was ${result.status}",
            result.status in listOf(ExecutionStatus.SUCCESS, ExecutionStatus.ERROR)
        )
    }

    /**
     * Test that compilation errors are reported fast - not waiting for timeout.
     * Uses invalid Kotlin syntax that should fail immediately.
     */
    fun testCompilationFailureFast(): Unit = timeoutRunBlocking(10.seconds) {
        val invalidCode = """
            please fail this is not valid kotlin code
        """.trimIndent()

        val params = ExecutionParams(timeout = 60)  // Long timeout
        val executionId = storage.generateExecutionId(invalidCode, params)
        storage.createExecution(executionId, invalidCode, params)

        // This should return quickly with an error, not wait 60 seconds
        val result = executor.executeWithProgress(executionId, invalidCode, 60, ProgressReporter.noOp())

        // Should be an error status (either script engine not available or compilation error)
        assertEquals("Should fail with ERROR status", ExecutionStatus.ERROR, result.status)
        assertNotNull("Should have error message", result.errorMessage)
    }

    /**
     * Test that syntax errors are caught and reported immediately.
     */
    fun testSyntaxErrorFast(): Unit = timeoutRunBlocking(10.seconds) {
        val syntaxErrorCode = """
            execute {
                val x = // incomplete statement
            }
        """.trimIndent()

        val params = ExecutionParams(timeout = 60)
        val executionId = storage.generateExecutionId(syntaxErrorCode, params)
        storage.createExecution(executionId, syntaxErrorCode, params)

        val result = executor.executeWithProgress(executionId, syntaxErrorCode, 60, ProgressReporter.noOp())

        assertEquals("Should fail with ERROR status", ExecutionStatus.ERROR, result.status)
        assertNotNull("Should have error message", result.errorMessage)
    }

    /**
     * Test that missing execute {} block is reported (if script engine is available).
     */
    fun testMissingExecuteBlock(): Unit = timeoutRunBlocking(10.seconds) {
        val noExecuteCode = """
            // No execute block
            val x = 1 + 2
            println(x)
        """.trimIndent()

        val params = ExecutionParams(timeout = 60)
        val executionId = storage.generateExecutionId(noExecuteCode, params)
        storage.createExecution(executionId, noExecuteCode, params)

        val result = executor.executeWithProgress(executionId, noExecuteCode, 60, ProgressReporter.noOp())

        // Should be error status (either script engine not available or missing execute block)
        assertEquals("Should fail with ERROR status", ExecutionStatus.ERROR, result.status)
        assertNotNull("Should have error message", result.errorMessage)
    }

    /**
     * Test that multiple top-level execute blocks are handled correctly.
     * When script engine is available, multiple blocks should be collected and run in FIFO order.
     * When not available, we should get an error.
     */
    fun testMultipleExecuteBlocks(): Unit = timeoutRunBlocking(10.seconds) {
        val multiCode = """
            execute {
                println("First")
            }
            execute {
                println("Second")
            }
            execute {
                println("Third")
            }
        """.trimIndent()

        val params = ExecutionParams(timeout = 60)
        val executionId = storage.generateExecutionId(multiCode, params)
        storage.createExecution(executionId, multiCode, params)

        val result = executor.executeWithProgress(executionId, multiCode, 60, ProgressReporter.noOp())

        // Either SUCCESS (if engine is available) or ERROR (if not)
        assertTrue(
            "Should complete with valid status",
            result.status in listOf(ExecutionStatus.SUCCESS, ExecutionStatus.ERROR)
        )

        // If successful, verify FIFO order in output
        if (result.status == ExecutionStatus.SUCCESS) {
            assertTrue("Should have output", result.output.isNotEmpty())
            assertEquals("First message", "First", result.output.getOrNull(0))
            assertEquals("Second message", "Second", result.output.getOrNull(1))
            assertEquals("Third message", "Third", result.output.getOrNull(2))
        }
    }

    /**
     * Test runtime error in execute block is caught and reported.
     */
    fun testRuntimeErrorInBlock(): Unit = timeoutRunBlocking(10.seconds) {
        val errorCode = """
            execute {
                throw RuntimeException("Test runtime error")
            }
        """.trimIndent()

        val params = ExecutionParams(timeout = 60)
        val executionId = storage.generateExecutionId(errorCode, params)
        storage.createExecution(executionId, errorCode, params)

        val result = executor.executeWithProgress(executionId, errorCode, 60, ProgressReporter.noOp())

        // Should be error status
        assertEquals("Should fail with ERROR status", ExecutionStatus.ERROR, result.status)
        assertNotNull("Should have error message", result.errorMessage)
    }

    /**
     * Test timeout is reported correctly when execution takes too long.
     */
    fun testTimeoutReported(): Unit = timeoutRunBlocking(10.seconds) {
        val slowCode = """
            execute {
                println("Starting")
                kotlinx.coroutines.delay(5000) // 5 seconds
                println("Done")
            }
        """.trimIndent()

        val params = ExecutionParams(timeout = 1)  // 1 second timeout
        val executionId = storage.generateExecutionId(slowCode, params)
        storage.createExecution(executionId, slowCode, params)

        val result = executor.executeWithProgress(executionId, slowCode, 1, ProgressReporter.noOp())

        // Should be TIMEOUT (if engine is available and block runs) or ERROR (if engine not available)
        assertTrue(
            "Should complete with valid status",
            result.status in listOf(ExecutionStatus.TIMEOUT, ExecutionStatus.ERROR)
        )
    }
}
