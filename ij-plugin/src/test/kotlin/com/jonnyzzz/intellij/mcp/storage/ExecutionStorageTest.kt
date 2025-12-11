/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.intellij.mcp.storage

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertNotEquals
import java.nio.file.Files

/**
 * Tests for ExecutionStorage.
 */
class ExecutionStorageTest : BasePlatformTestCase() {

    private lateinit var storage: ExecutionStorage

    override fun setUp() {
        super.setUp()
        storage = ExecutionStorage(project)
    }

    fun testGenerateExecutionId() {
        val code = "execute { ctx -> ctx.println(\"Hello\") }"
        val params = ExecutionParams(timeout = 60)

        val id1 = storage.generateExecutionId(code, params)

        // ID should have the format: {3-char-hash}-{timestamp}-{10-char-hash}
        // Example: abc-2024-12-10T14-30-25-a1B2c3D4e5
        val parts = id1.split("-")
        assertTrue("ID should have multiple parts separated by dash", parts.size >= 5)

        // First part should be 3-char project hash
        assertEquals("First part should be 3 chars", 3, parts[0].length)

        // Last part should be 10-char payload hash
        assertEquals("Last part should be 10 chars", 10, parts.last().length)

        // Different code should produce different payload hash
        val id3 = storage.generateExecutionId("different code", params)
        assertNotEquals("Different code should have different hash", id1.takeLast(10), id3.takeLast(10))
    }

    fun testCreateExecution() {
        val code = "execute { ctx -> ctx.println(\"Hello\") }"
        val params = ExecutionParams(timeout = 30)
        val executionId = storage.generateExecutionId(code, params)

        storage.createExecution(executionId, code, params)

        assertTrue("Execution should exist", storage.exists(executionId))

        val savedCode = storage.readScript(executionId)
        assertEquals("Script should be saved", code, savedCode)

        val savedParams = storage.readParams(executionId)
        assertNotNull("Params should be saved", savedParams)
        assertEquals("Timeout should match", 30, savedParams!!.timeout)
    }

    fun testAppendAndReadOutput() {
        val code = "test"
        val params = ExecutionParams()
        val executionId = storage.generateExecutionId(code, params)
        storage.createExecution(executionId, code, params)

        // Append multiple messages
        storage.appendOutput(executionId, OutputMessage(
            ts = 1000L,
            type = OutputType.OUT,
            msg = "Hello"
        ))
        storage.appendOutput(executionId, OutputMessage(
            ts = 2000L,
            type = OutputType.LOG,
            msg = "Info message",
            level = "info"
        ))
        storage.appendOutput(executionId, OutputMessage(
            ts = 3000L,
            type = OutputType.ERR,
            msg = "Error!"
        ))

        // Read all output
        val allOutput = storage.readOutput(executionId)
        assertEquals("Should have 3 messages", 3, allOutput.size)
        assertEquals("First message", "Hello", allOutput[0].msg)
        assertEquals("Second message type", OutputType.LOG, allOutput[1].type)
        assertEquals("Third message", "Error!", allOutput[2].msg)

        // Read with offset
        val offsetOutput = storage.readOutput(executionId, offset = 1)
        assertEquals("Should have 2 messages after offset", 2, offsetOutput.size)
        assertEquals("First after offset", "Info message", offsetOutput[0].msg)
    }

    fun testWriteAndReadResult() {
        val code = "test"
        val params = ExecutionParams()
        val executionId = storage.generateExecutionId(code, params)
        storage.createExecution(executionId, code, params)

        val result = ExecutionResult(
            status = ExecutionStatus.SUCCESS
        )
        storage.writeResult(executionId, result)

        val readResult = storage.readResult(executionId)
        assertNotNull("Result should be readable", readResult)
        assertEquals("Status should match", ExecutionStatus.SUCCESS, readResult!!.status)
    }

    fun testResultWithError() {
        val code = "test"
        val params = ExecutionParams()
        val executionId = storage.generateExecutionId(code, params)
        storage.createExecution(executionId, code, params)

        val result = ExecutionResult(
            status = ExecutionStatus.ERROR,
            errorMessage = "Something went wrong",
            exceptionInfo = "java.lang.RuntimeException: test\n\tat ..."
        )
        storage.writeResult(executionId, result)

        val readResult = storage.readResult(executionId)
        assertNotNull(readResult)
        assertEquals(ExecutionStatus.ERROR, readResult!!.status)
        assertEquals("Something went wrong", readResult.errorMessage)
        assertTrue(readResult.exceptionInfo?.contains("RuntimeException") == true)
    }

    fun testPendingReview() {
        val code = "execute { ctx -> ctx.println(\"Review me\") }"
        val params = ExecutionParams()
        val executionId = storage.generateExecutionId(code, params)

        val reviewFile = storage.savePendingReview(executionId, code)
        assertTrue("Review file should exist", Files.exists(reviewFile))

        val savedCode = Files.readString(reviewFile)
        assertEquals("Code should match", code, savedCode)

        storage.removePendingReview(executionId)
        assertFalse("Review file should be removed", Files.exists(reviewFile))
    }
}
