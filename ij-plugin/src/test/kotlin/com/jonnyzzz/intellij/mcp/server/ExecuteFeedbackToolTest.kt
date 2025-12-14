/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.intellij.mcp.server

import com.intellij.openapi.components.service
import com.intellij.testFramework.common.timeoutRunBlocking
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.jonnyzzz.intellij.mcp.mcp.McpHttpTransport
import com.jonnyzzz.intellij.mcp.mcp.McpJson
import com.jonnyzzz.intellij.mcp.mcp.JsonRpcResponse
import com.jonnyzzz.intellij.mcp.mcp.ToolCallResult
import com.jonnyzzz.intellij.mcp.storage.ExecutionParams
import com.jonnyzzz.intellij.mcp.storage.ExecutionStorage
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.serialization.json.decodeFromJsonElement
import kotlin.time.Duration.Companion.seconds

/**
 * Tests for the steroid_execute_feedback MCP tool.
 */
class ExecuteFeedbackToolTest : BasePlatformTestCase() {

    private lateinit var client: HttpClient
    private lateinit var storage: ExecutionStorage

    override fun setUp() {
        super.setUp()
        setRegistryPropertyForTest("mcp.steroids.review.mode", "NEVER")
        setRegistryPropertyForTest("mcp.steroids.server.port", "0")
        client = HttpClient(CIO) { expectSuccess = false }
        storage = project.service<ExecutionStorage>()
    }

    override fun tearDown() {
        try {
            client.close()
        } finally {
            super.tearDown()
        }
    }

    fun testFeedbackToolIsRegistered(): Unit = timeoutRunBlocking(30.seconds) {
        val server = SteroidsMcpServer.getInstance()
        server.startServerIfNeeded()

        // List tools
        val response = client.post(server.mcpUrl) {
            contentType(ContentType.Application.Json)
            setBody("""{"jsonrpc":"2.0","id":"1","method":"tools/list"}""")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertTrue("Response should contain execute_feedback tool", body.contains("steroid_execute_feedback"))
    }

    fun testFeedbackWithValidInput(): Unit = timeoutRunBlocking(30.seconds) {
        val server = SteroidsMcpServer.getInstance()
        server.startServerIfNeeded()

        val taskId = "test-feedback-task-${System.currentTimeMillis()}"
        val executionId = storage.generateExecutionId("println(1)", ExecutionParams())

        // Create execution and associate with task
        storage.createExecution(executionId, "println(1)", ExecutionParams())
        storage.addExecutionToTask(taskId, executionId, project.name)

        // Initialize session first
        val initResponse = client.post(server.mcpUrl) {
            contentType(ContentType.Application.Json)
            setBody(buildInitRequest())
        }
        val sessionId = initResponse.headers[McpHttpTransport.SESSION_HEADER]

        // Call feedback tool
        val feedbackBody = """
            {
                "jsonrpc": "2.0",
                "id": "feedback-1",
                "method": "tools/call",
                "params": {
                    "name": "steroid_execute_feedback",
                    "arguments": {
                        "project_name": "${project.name}",
                        "task_id": "$taskId",
                        "execution_id": "$executionId",
                        "success_rating": 0.85,
                        "explanation": "Execution completed as expected"
                    }
                }
            }
        """.trimIndent()

        val response = client.post(server.mcpUrl) {
            contentType(ContentType.Application.Json)
            header(McpHttpTransport.SESSION_HEADER, sessionId)
            setBody(feedbackBody)
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val rpc = McpJson.decodeFromString<JsonRpcResponse>(response.bodyAsText())
        assertNull("Feedback call should not return error", rpc.error)
        val result = McpJson.decodeFromJsonElement<ToolCallResult>(rpc.result!!)
        assertFalse("Feedback should succeed", result.isError)

        // Verify feedback was stored
        val storedFeedback = storage.readFeedback(taskId)
        assertNotNull("Feedback should be stored", storedFeedback)
        assertEquals(0.85, storedFeedback!!.successRating, 0.001)
    }

    fun testFeedbackWithInvalidRating(): Unit = timeoutRunBlocking(30.seconds) {
        val server = SteroidsMcpServer.getInstance()
        server.startServerIfNeeded()

        val taskId = "test-invalid-rating-${System.currentTimeMillis()}"
        val executionId = storage.generateExecutionId("println(1)", ExecutionParams())

        storage.createExecution(executionId, "println(1)", ExecutionParams())
        storage.addExecutionToTask(taskId, executionId, project.name)

        val initResponse = client.post(server.mcpUrl) {
            contentType(ContentType.Application.Json)
            setBody(buildInitRequest())
        }
        val sessionId = initResponse.headers[McpHttpTransport.SESSION_HEADER]

        // Try with rating > 1.0
        val feedbackBody = """
            {
                "jsonrpc": "2.0",
                "id": "feedback-invalid",
                "method": "tools/call",
                "params": {
                    "name": "steroid_execute_feedback",
                    "arguments": {
                        "project_name": "${project.name}",
                        "task_id": "$taskId",
                        "execution_id": "$executionId",
                        "success_rating": 1.5,
                        "explanation": "Invalid rating"
                    }
                }
            }
        """.trimIndent()

        val response = client.post(server.mcpUrl) {
            contentType(ContentType.Application.Json)
            header(McpHttpTransport.SESSION_HEADER, sessionId)
            setBody(feedbackBody)
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val rpc = McpJson.decodeFromString<JsonRpcResponse>(response.bodyAsText())
        val result = McpJson.decodeFromJsonElement<ToolCallResult>(rpc.result!!)
        assertTrue("Invalid rating should return error", result.isError)
    }

    fun testFeedbackWithNonExistentExecution(): Unit = timeoutRunBlocking(30.seconds) {
        val server = SteroidsMcpServer.getInstance()
        server.startServerIfNeeded()

        val initResponse = client.post(server.mcpUrl) {
            contentType(ContentType.Application.Json)
            setBody(buildInitRequest())
        }
        val sessionId = initResponse.headers[McpHttpTransport.SESSION_HEADER]

        val feedbackBody = """
            {
                "jsonrpc": "2.0",
                "id": "feedback-nonexistent",
                "method": "tools/call",
                "params": {
                    "name": "steroid_execute_feedback",
                    "arguments": {
                        "project_name": "${project.name}",
                        "task_id": "nonexistent-task",
                        "execution_id": "nonexistent-execution",
                        "success_rating": 0.5,
                        "explanation": "This execution doesn't exist"
                    }
                }
            }
        """.trimIndent()

        val response = client.post(server.mcpUrl) {
            contentType(ContentType.Application.Json)
            header(McpHttpTransport.SESSION_HEADER, sessionId)
            setBody(feedbackBody)
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val rpc = McpJson.decodeFromString<JsonRpcResponse>(response.bodyAsText())
        val result = McpJson.decodeFromJsonElement<ToolCallResult>(rpc.result!!)
        assertTrue("Non-existent execution should return error", result.isError)
        assertTrue("Error should mention execution not found",
            result.content.any { it.toString().contains("not found") })
    }

    fun testFeedbackWithMismatchedTaskId(): Unit = timeoutRunBlocking(30.seconds) {
        val server = SteroidsMcpServer.getInstance()
        server.startServerIfNeeded()

        val taskId = "test-mismatched-${System.currentTimeMillis()}"
        val executionId = storage.generateExecutionId("println(1)", ExecutionParams())

        // Create execution with one task ID
        storage.createExecution(executionId, "println(1)", ExecutionParams())
        storage.addExecutionToTask(taskId, executionId, project.name)

        val initResponse = client.post(server.mcpUrl) {
            contentType(ContentType.Application.Json)
            setBody(buildInitRequest())
        }
        val sessionId = initResponse.headers[McpHttpTransport.SESSION_HEADER]

        // Try to provide feedback with different task ID
        val feedbackBody = """
            {
                "jsonrpc": "2.0",
                "id": "feedback-mismatch",
                "method": "tools/call",
                "params": {
                    "name": "steroid_execute_feedback",
                    "arguments": {
                        "project_name": "${project.name}",
                        "task_id": "different-task-id",
                        "execution_id": "$executionId",
                        "success_rating": 0.5,
                        "explanation": "Wrong task ID"
                    }
                }
            }
        """.trimIndent()

        val response = client.post(server.mcpUrl) {
            contentType(ContentType.Application.Json)
            header(McpHttpTransport.SESSION_HEADER, sessionId)
            setBody(feedbackBody)
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val rpc = McpJson.decodeFromString<JsonRpcResponse>(response.bodyAsText())
        val result = McpJson.decodeFromJsonElement<ToolCallResult>(rpc.result!!)
        assertTrue("Mismatched task ID should return error", result.isError)
    }

    private fun buildInitRequest() = """
        {
            "jsonrpc": "2.0",
            "id": "init",
            "method": "initialize",
            "params": {
                "protocolVersion": "2024-11-05",
                "capabilities": {},
                "clientInfo": { "name": "test", "version": "1.0" }
            }
        }
    """.trimIndent()
}
