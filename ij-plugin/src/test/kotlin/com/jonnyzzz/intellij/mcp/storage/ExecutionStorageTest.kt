/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.intellij.mcp.storage

import com.intellij.openapi.components.service
import com.intellij.testFramework.common.timeoutRunBlocking
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.jonnyzzz.intellij.mcp.server.ExecCodeParams
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertNotEquals
import kotlin.time.Duration.Companion.seconds

/**
 * Tests for ExecutionStorage.
 *
 * Note: The storage API has been simplified to append-only logging.
 * Tests cover the current API: writeNewExecution, appendExecutionEvent,
 * writeCodeExecutionData, and findExecutionId.
 */
class ExecutionStorageTest : BasePlatformTestCase() {

    private lateinit var storage: ExecutionStorage

    override fun setUp() {
        super.setUp()
        storage = project.service()
    }

    private fun testExecParams(code: String, taskId: String = "test-task") = ExecCodeParams(
        taskId = taskId,
        code = code,
        reason = "test",
        timeout = 60,
        rawParams = buildJsonObject { }
    )

    fun testWriteNewExecution(): Unit = timeoutRunBlocking(10.seconds) {
        val code = "execute { println(\"Hello\") }"
        val params = testExecParams(code)

        val executionId = storage.writeNewExecution(params)

        // ID should have the format: eid_{timestamp}-{task-id}
        assertTrue("ID should start with eid_", executionId.executionId.startsWith("eid_"))
        assertTrue("ID should contain task ID", executionId.executionId.contains("test"))
    }

    fun testDifferentTaskIdsProduceDifferentExecutionIds(): Unit = timeoutRunBlocking(10.seconds) {
        val code = "execute { println(\"Hello\") }"

        val id1 = storage.writeNewExecution(testExecParams(code, taskId = "task-1"))
        val id2 = storage.writeNewExecution(testExecParams(code, taskId = "task-2"))

        assertNotEquals("Different task IDs should produce different execution IDs",
            id1.executionId, id2.executionId)
    }

    fun testFindExecutionId(): Unit = timeoutRunBlocking(10.seconds) {
        val code = "execute { println(\"Hello\") }"
        val executionId = storage.writeNewExecution(testExecParams(code))

        // Should find the execution
        val found = storage.findExecutionId(executionId.executionId)
        assertNotNull("Should find execution", found)
        assertEquals("Execution ID should match", executionId.executionId, found?.executionId)

        // Should not find non-existent execution
        val notFound = storage.findExecutionId("non-existent-id")
        assertNull("Should not find non-existent execution", notFound)
    }

    fun testFindExecutionIdRejectsInvalidPaths(): Unit = timeoutRunBlocking(10.seconds) {
        // Should reject paths with ".." or "/"
        assertNull(storage.findExecutionId("../etc/passwd"))
        assertNull(storage.findExecutionId("foo/bar"))
        assertNull(storage.findExecutionId(".."))
    }

    fun testAppendExecutionEvent(): Unit = timeoutRunBlocking(10.seconds) {
        val code = "test"
        val executionId = storage.writeNewExecution(testExecParams(code))

        // Should not throw
        storage.appendExecutionEvent(executionId, "Hello from test")
        storage.appendExecutionEvent(executionId, "Second message")
    }

    fun testWriteCodeExecutionData(): Unit = timeoutRunBlocking(10.seconds) {
        val code = "test"
        val executionId = storage.writeNewExecution(testExecParams(code))

        // Write custom data file
        val path = storage.writeCodeExecutionData(executionId, "custom.txt", "Custom content")
        assertTrue("File should exist", java.nio.file.Files.exists(path))

        val content = java.nio.file.Files.readString(path)
        assertEquals("Content should match", "Custom content", content)
    }

    fun testWriteCodeReviewFile(): Unit = timeoutRunBlocking(10.seconds) {
        val code = "execute { println(\"Review me\") }"
        val params = testExecParams(code)
        val executionId = storage.writeNewExecution(params)

        val reviewPath = storage.writeCodeReviewFile(executionId, params)
        assertTrue("Review file should exist", java.nio.file.Files.exists(reviewPath))

        val savedCode = java.nio.file.Files.readString(reviewPath)
        assertEquals("Code should match", code, savedCode)
    }

    fun testRemoveCodeReviewFile(): Unit = timeoutRunBlocking(10.seconds) {
        val code = "execute { println(\"Review me\") }"
        val params = testExecParams(code)
        val executionId = storage.writeNewExecution(params)

        val reviewPath = storage.writeCodeReviewFile(executionId, params)
        assertTrue("Review file should exist", java.nio.file.Files.exists(reviewPath))

        storage.removeCodeReviewFile(executionId)
        assertFalse("Review file should be removed", java.nio.file.Files.exists(reviewPath))
    }
}
