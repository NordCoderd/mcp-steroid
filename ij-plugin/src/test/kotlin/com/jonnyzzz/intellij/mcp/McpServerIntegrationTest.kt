/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.intellij.mcp

import com.intellij.testFramework.common.timeoutRunBlocking
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.jonnyzzz.intellij.mcp.mcp.*
import com.jonnyzzz.intellij.mcp.server.ListProjectsResponse
import com.jonnyzzz.intellij.mcp.server.SteroidsMcpServer
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlin.time.Duration.Companion.seconds

/**
 * Integration tests for MCP Server.
 * Verifies MCP HTTP handshake and tool flows against the real SteroidsMcpServer.
 */
class McpServerIntegrationTest : BasePlatformTestCase() {

    private lateinit var client: HttpClient

    override fun setUp() {
        super.setUp()
        setRegistryPropertyForTest("mcp.steroids.review.mode", "NEVER")
        setRegistryPropertyForTest("mcp.steroids.server.port", "0")
        client = HttpClient(CIO) {
            expectSuccess = false
        }
    }

    override fun tearDown() {
        try {
            client.close()
        } finally {
            super.tearDown()
        }
    }

    fun testMcpAgentHappyPath(): Unit = timeoutRunBlocking(30.seconds) {
        val server = SteroidsMcpServer.getInstance()
        server.startServerIfNeeded()

        val initResponse = client.post(server.mcpUrl) {
            contentType(ContentType.Application.Json)
            accept(ContentType.Application.Json)
            setBody(buildInitializeRequest())
        }

        assertEquals(HttpStatusCode.OK, initResponse.status)
        val sessionId = initResponse.headers[McpHttpTransport.SESSION_HEADER]
        assertNotNull("Server must issue MCP session id", sessionId)

        val initRpc = McpJson.decodeFromString<JsonRpcResponse>(initResponse.bodyAsText())
        assertNull("Initialize should not return error", initRpc.error)
        val initResult = McpJson.decodeFromJsonElement<InitializeResult>(initRpc.result!!)
        assertEquals(MCP_PROTOCOL_VERSION, initResult.protocolVersion)
        assertEquals("intellij-mcp-steroid", initResult.serverInfo.name)

        val toolsListResponse = client.post(server.mcpUrl) {
            contentType(ContentType.Application.Json)
            accept(ContentType.Application.Json)
            header(McpHttpTransport.SESSION_HEADER, sessionId)
            setBody("""{"jsonrpc":"2.0","id":"tools-list","method":"tools/list"}""")
        }

        assertEquals(HttpStatusCode.OK, toolsListResponse.status)
        val toolsRpc = McpJson.decodeFromString<JsonRpcResponse>(toolsListResponse.bodyAsText())
        assertNull("tools/list should succeed", toolsRpc.error)
        val toolsList = McpJson.decodeFromJsonElement<ToolsListResult>(toolsRpc.result!!)
        val toolNames = toolsList.tools.map { it.name }.toSet()
        assertTrue(
            "steroid tools should be advertised",
            toolNames.containsAll(setOf("steroid_list_projects", "steroid_execute_code"))
        )

        val listProjectsResponse = client.post(server.mcpUrl) {
            contentType(ContentType.Application.Json)
            accept(ContentType.Application.Json)
            header(McpHttpTransport.SESSION_HEADER, sessionId)
            setBody("""{"jsonrpc":"2.0","id":"call-list-projects","method":"tools/call","params":{"name":"steroid_list_projects"}}""")
        }

        assertEquals(HttpStatusCode.OK, listProjectsResponse.status)
        val listProjectsRpc = McpJson.decodeFromString<JsonRpcResponse>(listProjectsResponse.bodyAsText())
        assertNull("steroid_list_projects should not return JSON-RPC error", listProjectsRpc.error)
        val listProjectsResult = McpJson.decodeFromJsonElement<ToolCallResult>(listProjectsRpc.result!!)
        assertFalse("steroid_list_projects should succeed", listProjectsResult.isError)
        val projectsPayload = (listProjectsResult.content.single() as ContentItem.Text).text
        val projects = McpJson.decodeFromString<ListProjectsResponse>(projectsPayload)
        assertTrue(
            "Current project should be discoverable via MCP tool",
            projects.projects.any { it.name == project.name }
        )

        val execResponse = client.post(server.mcpUrl) {
            contentType(ContentType.Application.Json)
            accept(ContentType.Application.Json)
            header(McpHttpTransport.SESSION_HEADER, sessionId)
            setBody(buildExecuteCodeRequest(project.name))
        }

        assertEquals(HttpStatusCode.OK, execResponse.status)
        val execRpc = McpJson.decodeFromString<JsonRpcResponse>(execResponse.bodyAsText())
        assertNull("steroid_execute_code should return result payload", execRpc.error)
        val execResult = McpJson.decodeFromJsonElement<ToolCallResult>(execRpc.result!!)
        val execOutput = (execResult.content.singleOrNull() as? ContentItem.Text)?.text.orEmpty()
        assertTrue("steroid_execute_code should return content for the agent", execOutput.isNotBlank())
        assertFalse("Execution should succeed, got error payload: $execOutput", execResult.isError)
        assertTrue(
            "Execution output should include marker text, got: $execOutput",
            execOutput.contains("Integration test execution from MCP")
        )
    }

    private fun buildInitializeRequest() = buildJsonObject {
        put("jsonrpc", "2.0")
        put("id", "init-1")
        put("method", "initialize")
        putJsonObject("params") {
            put("protocolVersion", MCP_PROTOCOL_VERSION)
            putJsonObject("capabilities") {}
            putJsonObject("clientInfo") {
                put("name", "integration-test-client")
                put("version", "1.0.0")
            }
        }
    }.toString()

    private fun buildExecuteCodeRequest(projectName: String) = buildJsonObject {
        put("jsonrpc", "2.0")
        put("id", "execute-1")
        put("method", "tools/call")
        putJsonObject("params") {
            put("name", "steroid_execute_code")
            putJsonObject("arguments") {
                put("project_name", projectName)
                put(
                    "code",
                    """
                    execute {
                        println("Integration test execution from MCP")
                    }
                    """.trimIndent()
                )
                put("reason", "Verify MCP agent can execute code inside IntelliJ")
            }
        }
    }.toString()

    /**
     * Tests that the server responds correctly to GET requests with Claude CLI's Accept header.
     * Claude CLI sends "Accept: application/json, text/event-stream" for health checks.
     *
     * This was causing "Failed to connect" in Claude CLI because the server was returning
     * 405 Method Not Allowed when text/event-stream was in the Accept header.
     */
    fun testGetRequestWithClaudeCliAcceptHeader(): Unit = timeoutRunBlocking(30.seconds) {
        val server = SteroidsMcpServer.getInstance()
        server.startServerIfNeeded()

        // Claude CLI sends this Accept header format for health checks
        val response = client.get(server.mcpUrl) {
            header("Accept", "application/json, text/event-stream")
            header("User-Agent", "claude-code/2.0.67")
        }

        assertEquals(
            "GET with Claude CLI Accept header should return 200 OK",
            HttpStatusCode.OK,
            response.status
        )
        assertEquals(
            "Response should be JSON",
            ContentType.Application.Json.withoutParameters(),
            response.contentType()?.withoutParameters()
        )

        val body = response.bodyAsText()
        assertTrue(
            "Response should contain server name",
            body.contains("intellij-mcp-steroid")
        )
        assertTrue(
            "Response should indicate server is available",
            body.contains("available")
        )
    }

    /**
     * Tests that the server responds correctly to POST InitializeRequest with Claude CLI's format.
     * This verifies the full MCP handshake that Claude CLI expects.
     */
    fun testPostInitializeWithClaudeCliFormat(): Unit = timeoutRunBlocking(30.seconds) {
        val server = SteroidsMcpServer.getInstance()
        server.startServerIfNeeded()

        // Claude CLI sends InitializeRequest with specific capabilities
        val initRequest = """
        {
            "jsonrpc": "2.0",
            "id": "1",
            "method": "initialize",
            "params": {
                "protocolVersion": "2025-06-18",
                "clientInfo": {
                    "name": "claude-code",
                    "version": "2.0.67"
                },
                "capabilities": {
                    "roots": {
                        "listChanged": true
                    },
                    "sampling": {}
                }
            }
        }
        """.trimIndent()

        val response = client.post(server.mcpUrl) {
            contentType(ContentType.Application.Json)
            header("Accept", "application/json, text/event-stream")
            header("User-Agent", "claude-code/2.0.67")
            setBody(initRequest)
        }

        assertEquals(
            "InitializeRequest should return 200 OK",
            HttpStatusCode.OK,
            response.status
        )

        // Check for session ID header
        val sessionId = response.headers[McpHttpTransport.SESSION_HEADER]
        assertNotNull("Server should return Mcp-Session-Id header", sessionId)

        // Parse the response
        val body = response.bodyAsText()
        val rpcResponse = McpJson.decodeFromString<JsonRpcResponse>(body)

        assertNull("Response should not have error", rpcResponse.error)
        assertNotNull("Response should have result", rpcResponse.result)

        val initResult = McpJson.decodeFromJsonElement<InitializeResult>(rpcResponse.result!!)
        assertEquals("2025-06-18", initResult.protocolVersion)
        assertEquals("intellij-mcp-steroid", initResult.serverInfo.name)
        assertNotNull("Server should have tools capability", initResult.capabilities.tools)
    }

    /**
     * Tests with the EXACT request format Claude CLI sends (from debug logs).
     *
     * Log shows:
     * {"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{"roots":{}},"clientInfo":{"name":"claude-code","version":"2.0.67"}},"jsonrpc":"2.0","id":0}
     *
     * Key differences from our test:
     * - "id" is numeric 0, not string "1"
     * - "capabilities" has "roots":{} (empty object)
     * - Field order: method, params, jsonrpc, id (not jsonrpc first)
     */
    fun testExactClaudeCliInitializeRequest(): Unit = timeoutRunBlocking(30.seconds) {
        val server = SteroidsMcpServer.getInstance()
        server.startServerIfNeeded()

        // EXACT request from Claude CLI debug logs
        val exactClaudeRequest = """{"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{"roots":{}},"clientInfo":{"name":"claude-code","version":"2.0.67"}},"jsonrpc":"2.0","id":0}"""

        val response = client.post(server.mcpUrl) {
            contentType(ContentType.Application.Json)
            header("Accept", "application/json, text/event-stream")
            header("User-Agent", "claude-code/2.0.67")
            setBody(exactClaudeRequest)
        }

        println("[TEST] Response status: ${response.status}")
        println("[TEST] Response headers:")
        response.headers.forEach { name, values ->
            println("[TEST]   $name: ${values.joinToString(", ")}")
        }
        val body = response.bodyAsText()
        println("[TEST] Response body: $body")

        assertEquals(
            "InitializeRequest should return 200 OK",
            HttpStatusCode.OK,
            response.status
        )

        // Check for session ID header
        val sessionId = response.headers[McpHttpTransport.SESSION_HEADER]
        assertNotNull("Server should return Mcp-Session-Id header", sessionId)

        // Parse and verify the response
        val rpcResponse = McpJson.decodeFromString<JsonRpcResponse>(body)
        assertNull("Response should not have error: ${rpcResponse.error}", rpcResponse.error)
        assertNotNull("Response should have result", rpcResponse.result)

        // Verify response ID matches request ID (numeric 0)
        // Note: id is JsonElement, so we check the content
        assertTrue(
            "Response ID should be 0, got: ${rpcResponse.id}",
            rpcResponse.id.toString() == "0"
        )

        val initResult = McpJson.decodeFromJsonElement<InitializeResult>(rpcResponse.result!!)
        assertEquals("2025-06-18", initResult.protocolVersion)
        assertEquals("intellij-mcp-steroid", initResult.serverInfo.name)
    }

    /**
     * Tests that SSE-only GET requests receive 405 Method Not Allowed.
     * This is per MCP spec - if the server doesn't support SSE, it should return 405.
     */
    fun testGetRequestWithSseOnlyAcceptHeader(): Unit = timeoutRunBlocking(30.seconds) {
        val server = SteroidsMcpServer.getInstance()
        server.startServerIfNeeded()

        // Request SSE only (not JSON) - should get 405
        val response = client.get(server.mcpUrl) {
            header("Accept", "text/event-stream")
        }

        assertEquals(
            "GET with SSE-only Accept header should return 405",
            HttpStatusCode.MethodNotAllowed,
            response.status
        )
    }

    /**
     * Tests that subsequent requests with session ID work correctly.
     * This verifies the full session management flow.
     */
    fun testSessionManagementFlow(): Unit = timeoutRunBlocking(30.seconds) {
        val server = SteroidsMcpServer.getInstance()
        server.startServerIfNeeded()

        // Step 1: Initialize and get session ID
        val initResponse = client.post(server.mcpUrl) {
            contentType(ContentType.Application.Json)
            accept(ContentType.Application.Json)
            setBody(buildInitializeRequest())
        }

        assertEquals(HttpStatusCode.OK, initResponse.status)
        val sessionId = initResponse.headers[McpHttpTransport.SESSION_HEADER]
        assertNotNull("Session ID should be provided", sessionId)

        // Step 2: Make a request with the session ID
        val toolsResponse = client.post(server.mcpUrl) {
            contentType(ContentType.Application.Json)
            accept(ContentType.Application.Json)
            header(McpHttpTransport.SESSION_HEADER, sessionId)
            setBody("""{"jsonrpc":"2.0","id":"2","method":"tools/list"}""")
        }

        assertEquals(HttpStatusCode.OK, toolsResponse.status)
        val toolsRpc = McpJson.decodeFromString<JsonRpcResponse>(toolsResponse.bodyAsText())
        assertNull("tools/list should succeed with valid session", toolsRpc.error)

        // Step 3: Make a request with an invalid session ID (should create new session)
        val invalidSessionResponse = client.post(server.mcpUrl) {
            contentType(ContentType.Application.Json)
            accept(ContentType.Application.Json)
            header(McpHttpTransport.SESSION_HEADER, "invalid-session-id-12345")
            setBody("""{"jsonrpc":"2.0","id":"3","method":"tools/list"}""")
        }

        // Server should either reject the invalid session or create a new one
        // Per MCP spec, invalid sessions should return 400 Bad Request
        assertEquals(
            "Invalid session should be rejected",
            HttpStatusCode.BadRequest,
            invalidSessionResponse.status
        )
    }
}
