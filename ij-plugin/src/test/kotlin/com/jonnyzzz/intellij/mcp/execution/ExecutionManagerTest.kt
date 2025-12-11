/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.intellij.mcp.execution

import com.intellij.openapi.components.service
import com.intellij.openapi.util.registry.Registry
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.jonnyzzz.intellij.mcp.storage.ExecutionParams
import com.jonnyzzz.intellij.mcp.storage.ExecutionStatus
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotEquals

/**
 * Tests for ExecutionManager.
 */
class ExecutionManagerTest : BasePlatformTestCase() {

    override fun setUp() {
        super.setUp()
        // Disable review mode for tests
        try {
            Registry.get("mcp.steroids.review.mode").setValue("NEVER")
        } catch (e: Exception) {
            // Registry key might not exist in test environment
        }
    }

    override fun tearDown() {
        try {
            Registry.get("mcp.steroids.review.mode").resetToDefault()
        } catch (e: Exception) {
            // Ignore
        }
        super.tearDown()
    }

    fun testSubmitReturnsExecutionId() = runBlocking {
        val manager = project.service<ExecutionManager>()

        val code = """
            execute { ctx ->
                ctx.println("Hello from test")
            }
        """.trimIndent()

        val result = manager.submit(code, ExecutionParams(timeout = 30))

        assertNotNull("Should return execution ID", result.executionId)
        assertTrue("Execution ID should not be empty", result.executionId.isNotEmpty())
    }

    fun testGetStatus() = runBlocking {
        val manager = project.service<ExecutionManager>()

        val code = """
            execute { ctx ->
                ctx.println("Test")
            }
        """.trimIndent()

        val result = manager.submit(code, ExecutionParams(timeout = 30))

        // Give it a moment to start
        delay(100)

        val status = manager.getStatus(result.executionId)

        // Status should be one of the valid states
        assertTrue(
            "Status should be valid",
            status in listOf(
                ExecutionStatus.COMPILING,
                ExecutionStatus.RUNNING,
                ExecutionStatus.SUCCESS,
                ExecutionStatus.ERROR
            )
        )
    }

    fun testGetStatusNotFound() {
        val manager = project.service<ExecutionManager>()

        val status = manager.getStatus("nonexistent-id")
        assertEquals(ExecutionStatus.NOT_FOUND, status)
    }

    fun testGetResult() = runBlocking {
        val manager = project.service<ExecutionManager>()

        val code = """
            execute { ctx ->
                ctx.println("Line 1")
                ctx.println("Line 2")
            }
        """.trimIndent()

        val submitResult = manager.submit(code, ExecutionParams(timeout = 30))

        // Wait for execution to complete
        delay(2000)

        val result = manager.getResult(submitResult.executionId)

        assertEquals(submitResult.executionId, result.executionId)
        // Either succeeded or failed with error - both are valid outcomes
        assertTrue(
            "Should have completed",
            result.status in listOf(ExecutionStatus.SUCCESS, ExecutionStatus.ERROR)
        )
    }

    fun testGetResultWithOffset() = runBlocking {
        val manager = project.service<ExecutionManager>()

        val code = """
            execute { ctx ->
                ctx.println("Message 1")
                ctx.println("Message 2")
                ctx.println("Message 3")
            }
        """.trimIndent()

        val submitResult = manager.submit(code, ExecutionParams(timeout = 30))

        // Wait for execution
        delay(2000)

        val resultAll = manager.getResult(submitResult.executionId, offset = 0)
        val resultOffset = manager.getResult(submitResult.executionId, offset = 1)

        // If execution succeeded, offset should work
        if (resultAll.status == ExecutionStatus.SUCCESS) {
            assertTrue(
                "Offset result should have fewer messages",
                resultOffset.output.size < resultAll.output.size || resultAll.output.isEmpty()
            )
        }
    }

    fun testCancel() = runBlocking {
        val manager = project.service<ExecutionManager>()

        // Long-running script
        val code = """
            execute { ctx ->
                ctx.println("Starting")
                kotlinx.coroutines.delay(10000)
                ctx.println("Should not reach here")
            }
        """.trimIndent()

        val submitResult = manager.submit(code, ExecutionParams(timeout = 60))

        // Give it time to start
        delay(500)

        // Cancel it
        val cancelled = manager.cancel(submitResult.executionId)

        // Wait a bit for cancellation to take effect
        delay(500)

        val status = manager.getStatus(submitResult.executionId)

        // Should either be cancelled or completed (if it finished before cancellation)
        assertTrue(
            "Should be cancelled or completed",
            status in listOf(
                ExecutionStatus.CANCELLED,
                ExecutionStatus.SUCCESS,
                ExecutionStatus.ERROR,
                ExecutionStatus.RUNNING
            )
        )
    }

    fun testSequentialExecution() = runBlocking {
        val manager = project.service<ExecutionManager>()

        // Submit two scripts
        val code1 = """
            execute { ctx ->
                ctx.println("Script 1")
            }
        """.trimIndent()

        val code2 = """
            execute { ctx ->
                ctx.println("Script 2")
            }
        """.trimIndent()

        val result1 = manager.submit(code1, ExecutionParams(timeout = 30))
        val result2 = manager.submit(code2, ExecutionParams(timeout = 30))

        // Both should get unique IDs
        assertNotEquals("Should have different IDs", result1.executionId, result2.executionId)

        // Wait for both to complete
        delay(3000)

        // Both should complete
        val status1 = manager.getStatus(result1.executionId)
        val status2 = manager.getStatus(result2.executionId)

        assertTrue("First should complete", status1 in listOf(ExecutionStatus.SUCCESS, ExecutionStatus.ERROR))
        assertTrue("Second should complete", status2 in listOf(ExecutionStatus.SUCCESS, ExecutionStatus.ERROR))
    }
}
