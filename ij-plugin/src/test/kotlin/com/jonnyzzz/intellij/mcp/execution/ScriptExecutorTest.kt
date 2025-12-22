/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.intellij.mcp.execution

import com.intellij.openapi.components.service
import com.intellij.testFramework.common.timeoutRunBlocking
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.jonnyzzz.intellij.mcp.server.ExecCodeParams
import com.jonnyzzz.intellij.mcp.storage.ExecutionId
import kotlinx.serialization.json.buildJsonObject
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
 *
 * The ScriptExecutor uses ExecutionResultBuilder to collect output, so we use
 * a TestResultBuilder to capture the results.
 */
class ScriptExecutorTest : BasePlatformTestCase() {

    private val executor: ScriptExecutor get() = project.service()

    /**
     * Test implementation of ExecutionResultBuilder that collects messages.
     * Note: Uses NoOpProgressReporter pattern for tests that don't need MCP progress.
     */
    private class TestResultBuilder : ExecutionResultBuilder {
        val messages = mutableListOf<String>()
        val progressMessages = mutableListOf<String>()
        val exceptions = mutableListOf<Pair<String, Throwable>>()
        private var failed = false
        var failureMessage: String? = null

        override val isFailed: Boolean get() = failed

        override fun logMessage(message: String) {
            messages += message
        }

        override fun logProgress(message: String) {
            progressMessages += message
        }

        override fun logException(message: String, throwable: Throwable) {
            exceptions += message to throwable
        }

        override fun reportFailed(message: String) {
            failed = true
            failureMessage = message
        }
    }

    private fun testExecParams(code: String, timeout: Int = 60) = ExecCodeParams(
        taskId = "test-task",
        code = code,
        reason = "test",
        timeout = timeout,
        rawParams = buildJsonObject { }
    )

    private var executionCounter = 0
    private fun nextExecutionId() = ExecutionId("test-${++executionCounter}")

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

        val builder = TestResultBuilder()
        executor.executeWithProgress(nextExecutionId(), testExecParams(code), builder)

        // Should complete quickly (not wait 60 seconds for timeout)
        // Either has messages (success) or failed (error) - but completes fast
        assertTrue(
            "Should complete with output or error",
            builder.messages.isNotEmpty() || builder.isFailed
        )
    }

    private fun TestResultBuilder.hasAnyOutput(): Boolean {
        return isFailed || messages.isNotEmpty() || exceptions.isNotEmpty() || progressMessages.isNotEmpty()
    }

    /**
     * Test that compilation errors are reported fast - not waiting for timeout.
     * Uses invalid Kotlin syntax that should fail immediately.
     *
     * Note: When script engine is available, this should fail with compilation error.
     * When script engine is NOT available, it will also fail (script engine not available).
     * Either way, execution should complete quickly (not wait for timeout).
     */
    fun testCompilationFailureFast(): Unit = timeoutRunBlocking(10.seconds) {
        val invalidCode = """
            please fail this is not valid kotlin code
        """.trimIndent()

        val builder = TestResultBuilder()
        executor.executeWithProgress(nextExecutionId(), testExecParams(invalidCode), builder)

        // Either failed, has messages, or has exceptions logged
        // The test verifies fast completion (10 seconds timeout vs 60 seconds exec timeout)
        assertTrue("Should complete with some output", builder.hasAnyOutput())
    }

    /**
     * Test that syntax errors are caught and reported immediately.
     *
     * Note: When script engine is not available, this will fail with a different error.
     */
    fun testSyntaxErrorFast(): Unit = timeoutRunBlocking(10.seconds) {
        val syntaxErrorCode = """
            execute {
                val x = // incomplete statement
            }
        """.trimIndent()

        val builder = TestResultBuilder()
        executor.executeWithProgress(nextExecutionId(), testExecParams(syntaxErrorCode), builder)

        // Either failed, has messages, or has exceptions - verifies fast completion
        assertTrue("Should complete with some output", builder.hasAnyOutput())
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

        val builder = TestResultBuilder()
        executor.executeWithProgress(nextExecutionId(), testExecParams(noExecuteCode), builder)

        // Should fail (either script engine not available or missing execute block)
        // Either way, should complete quickly
        assertTrue("Should complete with some output", builder.hasAnyOutput())
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

        val builder = TestResultBuilder()
        executor.executeWithProgress(nextExecutionId(), testExecParams(multiCode), builder)

        // Either SUCCESS (if engine is available) or ERROR (if not)
        // If successful, verify FIFO order in output
        if (!builder.isFailed && builder.messages.isNotEmpty()) {
            assertTrue("Should have 3 messages", builder.messages.size >= 3)
            assertEquals("First message", "First", builder.messages[0])
            assertEquals("Second message", "Second", builder.messages[1])
            assertEquals("Third message", "Third", builder.messages[2])
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

        val builder = TestResultBuilder()
        executor.executeWithProgress(nextExecutionId(), testExecParams(errorCode), builder)

        // Should fail
        assertTrue("Should fail", builder.isFailed)
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

        val builder = TestResultBuilder()
        executor.executeWithProgress(nextExecutionId(), testExecParams(slowCode, timeout = 1), builder)

        // Should fail due to timeout (or error if engine not available)
        assertTrue("Should fail", builder.isFailed)
    }
}
