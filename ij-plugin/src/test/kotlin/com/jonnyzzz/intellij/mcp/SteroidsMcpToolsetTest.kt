/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.intellij.mcp

import com.intellij.openapi.util.registry.Registry
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.jonnyzzz.intellij.mcp.storage.ExecutionStatus
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking

/**
 * Tests for SteroidsMcpToolset - the MCP tool interface.
 */
class SteroidsMcpToolsetTest : BasePlatformTestCase() {

    private lateinit var toolset: SteroidsMcpToolset

    override fun setUp() {
        super.setUp()
        toolset = SteroidsMcpToolset()

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

    fun testListProjects() = runBlocking {
        val projects = toolset.list_projects()

        // Should include at least the test project
        assertTrue("Should have at least one project", projects.isNotEmpty())

        val testProject = projects.find { it.name == project.name }
        assertNotNull("Should find test project", testProject)
    }

    fun testExecuteCodeProjectNotFound() = runBlocking {
        val response = toolset.execute_code(
            project_name = "NonExistentProject12345",
            code = "execute { ctx -> ctx.println(\"Hello\") }"
        )

        assertEquals(ExecutionStatus.ERROR, response.status)
        assertTrue(response.error_message?.contains("not found") == true)
    }

    fun testExecuteCodeSuccess() = runBlocking {
        val response = toolset.execute_code(
            project_name = project.name,
            code = """
                execute { ctx ->
                    ctx.println("Hello from toolset test")
                }
            """.trimIndent(),
            timeout = 30
        )

        assertNotNull(response.execution_id)
        assertTrue(response.execution_id.isNotEmpty())

        // Initial status should be compiling or running
        assertTrue(
            "Should be in progress",
            response.status in listOf(
                ExecutionStatus.COMPILING,
                ExecutionStatus.RUNNING,
                ExecutionStatus.SUCCESS
            )
        )
    }

    fun testGetResultNotFound() = runBlocking {
        val response = toolset.get_result(
            execution_id = "nonexistent-execution-id",
            offset = 0
        )

        assertEquals(ExecutionStatus.NOT_FOUND, response.status)
    }

    fun testExecuteAndGetResult() = runBlocking {
        // Execute code
        val execResponse = toolset.execute_code(
            project_name = project.name,
            code = """
                execute { ctx ->
                    ctx.println("Test output")
                }
            """.trimIndent(),
            timeout = 30
        )

        // Wait for execution
        delay(2000)

        // Get result
        val resultResponse = toolset.get_result(
            execution_id = execResponse.execution_id,
            offset = 0,
        )

        assertEquals(execResponse.execution_id, resultResponse.execution_id)

        // Should have completed (success or error)
        assertTrue(
            "Should have completed",
            resultResponse.status in listOf(ExecutionStatus.SUCCESS, ExecutionStatus.ERROR)
        )
    }

    fun testCancelNotFound() = runBlocking {
        val response = toolset.cancel_execution("nonexistent-id")
        assertFalse(response.cancelled)
    }

    fun testOutputWithOffset() = runBlocking {
        // Execute code that produces multiple output lines
        val execResponse = toolset.execute_code(
            project_name = project.name,
            code = """
                execute { ctx ->
                    ctx.println("Line 1")
                    ctx.println("Line 2")
                    ctx.println("Line 3")
                }
            """.trimIndent(),
            timeout = 30
        )

        // Wait for execution
        delay(2000)

        // Get all output
        val fullResult = toolset.get_result(execResponse.execution_id, offset = 0)

        // Get with offset
        val offsetResult = toolset.get_result(execResponse.execution_id, offset = 1)

        // If execution succeeded and produced output
        if (fullResult.status == ExecutionStatus.SUCCESS && fullResult.output.isNotEmpty()) {
            assertTrue(
                "Offset should have fewer messages",
                offsetResult.output.size < fullResult.output.size
            )
        }
    }

    fun testProjectHashConsistency() = runBlocking {
        // Execute two scripts for the same project
        val response1 = toolset.execute_code(
            project_name = project.name,
            code = "execute { ctx -> ctx.println(\"1\") }",
            timeout = 30,
        )

        val response2 = toolset.execute_code(
            project_name = project.name,
            code = "execute { ctx -> ctx.println(\"2\") }",
            timeout = 30
        )

        // Both execution IDs should start with the same project hash
        val hash1 = response1.execution_id.split("-").first()
        val hash2 = response2.execution_id.split("-").first()

        assertEquals("Project hash should be consistent", hash1, hash2)
    }
}
