/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.intellij.mcp.review

import com.intellij.openapi.components.service
import com.intellij.openapi.util.registry.Registry
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.jonnyzzz.intellij.mcp.storage.ExecutionParams
import com.jonnyzzz.intellij.mcp.storage.ExecutionStorage

/**
 * Unit tests for ReviewManager.
 * Tests utility methods and storage interactions without full review workflow.
 * Full integration tests for review flow require UI interaction and are tested manually.
 */
class ReviewManagerTest : BasePlatformTestCase() {

    private lateinit var reviewManager: ReviewManager
    private lateinit var storage: ExecutionStorage

    override fun setUp() {
        super.setUp()
        reviewManager = project.service()
        storage = project.service()

        // Disable review mode for tests to avoid hanging
        try {
            Registry.get("mcp.steroids.review.mode").setValue("NEVER")
        } catch (e: Exception) {
            // Registry key might not exist
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

    fun testGetExecutionIdFromPath() {
        // Valid paths
        assertEquals(
            "abc-2024-01-01T12-00-00-xyz1234567",
            reviewManager.getExecutionIdFromPath("/path/to/.idea/mcp-run/abc-2024-01-01T12-00-00-xyz1234567/review.kts")
        )

        assertEquals(
            "exec-id",
            reviewManager.getExecutionIdFromPath("/mcp-run/exec-id/review.kts")
        )

        // Invalid paths - these return null because they don't match the expected pattern
        assertNull(reviewManager.getExecutionIdFromPath("/path/to/some/file.kts"))
        assertNull(reviewManager.getExecutionIdFromPath("/path/to/mcp-run/"))

        // Note: /mcp-run/review.kts would return "review.kts" as it extracts the segment after mcp-run
        // This is technically incorrect usage - the real path should have an execution-id folder
    }

    fun testHasPendingReviewInitiallyFalse() {
        assertFalse(reviewManager.hasPendingReview("nonexistent-id"))
    }

    fun testReviewCodeStorageRoundTrip() {
        val code = "execute { ctx -> ctx.println(\"test\") }"
        val params = ExecutionParams()
        val executionId = storage.generateExecutionId(code, params)
        storage.createExecution(executionId, code, params)

        // Save review code
        val reviewFile = storage.saveReviewCode(executionId, code)
        assertTrue("Review file should exist", java.nio.file.Files.exists(reviewFile))

        // Read back
        val readCode = storage.readReviewCode(executionId)
        assertEquals(code, readCode)
    }

    fun testReviewCodeWithEdits() {
        val originalCode = """
            execute { ctx ->
                ctx.println("Hello")
            }
        """.trimIndent()

        val editedCode = """
            execute { ctx ->
                // FIX: Please use proper error handling
                ctx.println("Hello")
                ctx.logInfo("Done")
            }
        """.trimIndent()

        val params = ExecutionParams()
        val executionId = storage.generateExecutionId(originalCode, params)
        storage.createExecution(executionId, originalCode, params)

        // Save original
        storage.saveReviewCode(executionId, originalCode)
        assertEquals(originalCode, storage.readReviewCode(executionId))

        // Simulate user edit - overwrite with edited code
        storage.saveReviewCode(executionId, editedCode)
        assertEquals(editedCode, storage.readReviewCode(executionId))
    }

    fun testReviewOutcomeStorageApproved() {
        val code = "test code"
        val params = ExecutionParams()
        val executionId = storage.generateExecutionId(code, params)
        storage.createExecution(executionId, code, params)

        val outcome = com.jonnyzzz.intellij.mcp.storage.ReviewOutcome(
            approved = true,
            originalCode = code
        )
        storage.saveReviewResult(executionId, outcome)

        val readOutcome = storage.readReviewResult(executionId)
        assertNotNull(readOutcome)
        assertTrue(readOutcome!!.approved)
        assertEquals(code, readOutcome.originalCode)
        assertNull(readOutcome.editedCode)
        assertNull(readOutcome.diff)
    }

    fun testReviewOutcomeStorageRejectedWithDiff() {
        val originalCode = "line1\nline2"
        val editedCode = "line1\nline2 modified\nline3"
        val diff = "--- original\n+++ edited\n@@ ... @@"

        val params = ExecutionParams()
        val executionId = storage.generateExecutionId(originalCode, params)
        storage.createExecution(executionId, originalCode, params)

        val outcome = com.jonnyzzz.intellij.mcp.storage.ReviewOutcome(
            approved = false,
            originalCode = originalCode,
            editedCode = editedCode,
            diff = diff
        )
        storage.saveReviewResult(executionId, outcome)

        val readOutcome = storage.readReviewResult(executionId)
        assertNotNull(readOutcome)
        assertFalse(readOutcome!!.approved)
        assertEquals(originalCode, readOutcome.originalCode)
        assertEquals(editedCode, readOutcome.editedCode)
        assertEquals(diff, readOutcome.diff)
    }

    fun testReviewFilePath() {
        val code = "test"
        val params = ExecutionParams()
        val executionId = storage.generateExecutionId(code, params)
        storage.createExecution(executionId, code, params)

        val reviewPath = storage.getReviewFilePath(executionId)
        assertTrue("Path should end with review.kts", reviewPath.toString().endsWith("review.kts"))
        assertTrue("Path should contain execution ID", reviewPath.toString().contains(executionId))
    }
}
