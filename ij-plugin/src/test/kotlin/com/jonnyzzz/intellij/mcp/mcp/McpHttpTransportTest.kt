/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.intellij.mcp.mcp

import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.cio.CIO
import io.ktor.server.engine.*
import io.ktor.server.routing.*
import io.ktor.server.sse.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.net.ServerSocket

/**
 * Integration tests for McpHttpTransport.
 * Tests the full HTTP transport layer with Ktor server.
 */
class McpHttpTransportTest {
    private lateinit var server: EmbeddedServer<*, *>
    private lateinit var mcpServer: McpServerCore
    private lateinit var client: HttpClient
    private var port: Int = 0

    @Before
    fun setUp() {
        port = ServerSocket(0).use { it.localPort }

        mcpServer = McpServerCore(
            serverInfo = ServerInfo(
                name = "test-server",
                version = "1.0.0"
            ),
            capabilities = ServerCapabilities(
                tools = ToolsCapability(listChanged = false)
            )
        )

        // Register a test tool
        mcpServer.toolRegistry.registerTool(
            name = "test_echo",
            description = "Echo tool for testing",
            inputSchema = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("message") { put("type", "string") }
                }
            }
        ) { context ->
            val message = context.params.arguments?.get("message")?.jsonPrimitive?.content ?: ""
            ToolCallResult(content = listOf(ContentItem.Text(text = "Echo: $message")))
        }

        server = embeddedServer(CIO, port = port) {
            install(SSE)
            routing {
                with(McpHttpTransport) {
                    installMcp("/mcp", mcpServer)
                }
            }
        }
        server.start(wait = false)

        client = HttpClient(io.ktor.client.engine.cio.CIO)
    }

    @After
    fun tearDown() {
        client.close()
        server.stop(100, 100)
    }

    @Test
    fun `test POST initialize creates session and returns response`() = runBlocking {
        val request = buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", 1)
            put("method", "initialize")
            putJsonObject("params") {
                put("protocolVersion", MCP_PROTOCOL_VERSION)
                putJsonObject("capabilities") {}
                putJsonObject("clientInfo") {
                    put("name", "test-client")
                    put("version", "1.0.0")
                }
            }
        }

        val response = client.post("http://localhost:$port/mcp") {
            contentType(ContentType.Application.Json)
            accept(ContentType.Application.Json)
            setBody(request.toString())
        }

        assertEquals(HttpStatusCode.OK, response.status)

        // Should have session header
        val sessionId = response.headers[McpHttpTransport.SESSION_HEADER]
        assertNotNull("Should return session ID", sessionId)

        // Parse response
        val body = response.bodyAsText()
        val jsonResponse = McpJson.decodeFromString<JsonRpcResponse>(body)
        assertNull(jsonResponse.error)
        assertNotNull(jsonResponse.result)

        val result = McpJson.decodeFromJsonElement<InitializeResult>(jsonResponse.result!!)
        assertEquals("test-server", result.serverInfo.name)
    }

    @Test
    fun `test POST with existing session ID reuses session`() = runBlocking {
        // First request creates session
        val initRequest = buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", 1)
            put("method", "initialize")
            putJsonObject("params") {
                put("protocolVersion", MCP_PROTOCOL_VERSION)
                putJsonObject("capabilities") {}
                putJsonObject("clientInfo") {
                    put("name", "test-client")
                    put("version", "1.0.0")
                }
            }
        }

        val firstResponse = client.post("http://localhost:$port/mcp") {
            contentType(ContentType.Application.Json)
            accept(ContentType.Application.Json)
            setBody(initRequest.toString())
        }

        val sessionId = firstResponse.headers[McpHttpTransport.SESSION_HEADER]
        assertNotNull(sessionId)

        // Second request with session ID
        val pingRequest = """{"jsonrpc":"2.0","id":2,"method":"ping"}"""

        val secondResponse = client.post("http://localhost:$port/mcp") {
            contentType(ContentType.Application.Json)
            accept(ContentType.Application.Json)
            header(McpHttpTransport.SESSION_HEADER, sessionId)
            setBody(pingRequest)
        }

        assertEquals(HttpStatusCode.OK, secondResponse.status)
    }

    @Test
    fun `test POST tools list returns tools`() = runBlocking {
        // First initialize
        val initRequest = buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", 1)
            put("method", "initialize")
            putJsonObject("params") {
                put("protocolVersion", MCP_PROTOCOL_VERSION)
                putJsonObject("capabilities") {}
                putJsonObject("clientInfo") {
                    put("name", "test-client")
                    put("version", "1.0.0")
                }
            }
        }

        val initResponse = client.post("http://localhost:$port/mcp") {
            contentType(ContentType.Application.Json)
            accept(ContentType.Application.Json)
            setBody(initRequest.toString())
        }
        val sessionId = initResponse.headers[McpHttpTransport.SESSION_HEADER]

        // Then list tools
        val listRequest = """{"jsonrpc":"2.0","id":2,"method":"tools/list"}"""

        val response = client.post("http://localhost:$port/mcp") {
            contentType(ContentType.Application.Json)
            accept(ContentType.Application.Json)
            header(McpHttpTransport.SESSION_HEADER, sessionId)
            setBody(listRequest)
        }

        assertEquals(HttpStatusCode.OK, response.status)

        val body = response.bodyAsText()
        val jsonResponse = McpJson.decodeFromString<JsonRpcResponse>(body)
        assertNull(jsonResponse.error)

        val result = McpJson.decodeFromJsonElement<ToolsListResult>(jsonResponse.result!!)
        assertTrue("Should have at least one tool", result.tools.isNotEmpty())
        assertEquals("test_echo", result.tools[0].name)
    }

    @Test
    fun `test POST tools call invokes tool`() = runBlocking {
        // First initialize
        val initRequest = buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", 1)
            put("method", "initialize")
            putJsonObject("params") {
                put("protocolVersion", MCP_PROTOCOL_VERSION)
                putJsonObject("capabilities") {}
                putJsonObject("clientInfo") {
                    put("name", "test-client")
                    put("version", "1.0.0")
                }
            }
        }

        val initResponse = client.post("http://localhost:$port/mcp") {
            contentType(ContentType.Application.Json)
            accept(ContentType.Application.Json)
            setBody(initRequest.toString())
        }
        val sessionId = initResponse.headers[McpHttpTransport.SESSION_HEADER]

        // Then call tool
        val callRequest = buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", 2)
            put("method", "tools/call")
            putJsonObject("params") {
                put("name", "test_echo")
                putJsonObject("arguments") {
                    put("message", "Hello World")
                }
            }
        }

        val response = client.post("http://localhost:$port/mcp") {
            contentType(ContentType.Application.Json)
            accept(ContentType.Application.Json)
            header(McpHttpTransport.SESSION_HEADER, sessionId)
            setBody(callRequest.toString())
        }

        assertEquals(HttpStatusCode.OK, response.status)

        val body = response.bodyAsText()
        val jsonResponse = McpJson.decodeFromString<JsonRpcResponse>(body)
        assertNull(jsonResponse.error)

        val result = McpJson.decodeFromJsonElement<ToolCallResult>(jsonResponse.result!!)
        assertEquals("Echo: Hello World", (result.content[0] as ContentItem.Text).text)
    }

    @Test
    fun `test POST notification returns Accepted`() = runBlocking {
        // First initialize
        val initRequest = buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", 1)
            put("method", "initialize")
            putJsonObject("params") {
                put("protocolVersion", MCP_PROTOCOL_VERSION)
                putJsonObject("capabilities") {}
                putJsonObject("clientInfo") {
                    put("name", "test-client")
                    put("version", "1.0.0")
                }
            }
        }

        val initResponse = client.post("http://localhost:$port/mcp") {
            contentType(ContentType.Application.Json)
            accept(ContentType.Application.Json)
            setBody(initRequest.toString())
        }
        val sessionId = initResponse.headers[McpHttpTransport.SESSION_HEADER]

        // Send notification (no id)
        val notification = """{"jsonrpc":"2.0","method":"notifications/initialized"}"""

        val response = client.post("http://localhost:$port/mcp") {
            contentType(ContentType.Application.Json)
            accept(ContentType.Application.Json)
            header(McpHttpTransport.SESSION_HEADER, sessionId)
            setBody(notification)
        }

        assertEquals(HttpStatusCode.Accepted, response.status)
    }

    @Test
    fun `test POST empty body returns BadRequest`() = runBlocking {
        val response = client.post("http://localhost:$port/mcp") {
            contentType(ContentType.Application.Json)
            accept(ContentType.Application.Json)
            setBody("")
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `test POST with unknown session creates new session`() = runBlocking {
        // Server should create a new session for unknown session IDs (supports IDE restart)
        val request = """{"jsonrpc":"2.0","id":1,"method":"tools/list"}"""

        val response = client.post("http://localhost:$port/mcp") {
            contentType(ContentType.Application.Json)
            accept(ContentType.Application.Json)
            header(McpHttpTransport.SESSION_HEADER, "unknown-session-id")
            setBody(request)
        }

        // Server should accept the request and create a new session
        assertEquals(HttpStatusCode.OK, response.status)

        // Server should return a new session ID
        val newSessionId = response.headers[McpHttpTransport.SESSION_HEADER]
        assertNotNull("Server should return new session ID", newSessionId)
        assertNotEquals("unknown-session-id", newSessionId)
    }

    @Test
    fun `test DELETE terminates session`() = runBlocking {
        // First create a session via initialize
        val initRequest = buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", 1)
            put("method", "initialize")
            putJsonObject("params") {
                put("protocolVersion", MCP_PROTOCOL_VERSION)
                putJsonObject("capabilities") {}
                putJsonObject("clientInfo") {
                    put("name", "test-client")
                    put("version", "1.0.0")
                }
            }
        }

        val initResponse = client.post("http://localhost:$port/mcp") {
            contentType(ContentType.Application.Json)
            accept(ContentType.Application.Json)
            setBody(initRequest.toString())
        }
        val sessionId = initResponse.headers[McpHttpTransport.SESSION_HEADER]

        // Delete session
        val deleteResponse = client.delete("http://localhost:$port/mcp") {
            header(McpHttpTransport.SESSION_HEADER, sessionId)
        }

        assertEquals(HttpStatusCode.NoContent, deleteResponse.status)

        // After deletion, requests with the old session ID should create a new session
        // (same as unknown session handling for IDE restart support)
        val listRequest = """{"jsonrpc":"2.0","id":2,"method":"tools/list"}"""
        val afterDeleteResponse = client.post("http://localhost:$port/mcp") {
            contentType(ContentType.Application.Json)
            accept(ContentType.Application.Json)
            header(McpHttpTransport.SESSION_HEADER, sessionId)
            setBody(listRequest)
        }

        assertEquals(HttpStatusCode.OK, afterDeleteResponse.status)

        // Server should return a new session ID
        val newSessionId = afterDeleteResponse.headers[McpHttpTransport.SESSION_HEADER]
        assertNotNull("Server should return new session ID after deleted session", newSessionId)
        assertNotEquals(sessionId, newSessionId, "New session ID should be different")
    }

    @Test
    fun `test DELETE without session ID returns BadRequest`() = runBlocking {
        val response = client.delete("http://localhost:$port/mcp")

        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `test batch request processing`() = runBlocking {
        // First initialize
        val initRequest = buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", 1)
            put("method", "initialize")
            putJsonObject("params") {
                put("protocolVersion", MCP_PROTOCOL_VERSION)
                putJsonObject("capabilities") {}
                putJsonObject("clientInfo") {
                    put("name", "test-client")
                    put("version", "1.0.0")
                }
            }
        }

        val initResponse = client.post("http://localhost:$port/mcp") {
            contentType(ContentType.Application.Json)
            accept(ContentType.Application.Json)
            setBody(initRequest.toString())
        }
        val sessionId = initResponse.headers[McpHttpTransport.SESSION_HEADER]

        // Send batch request
        val batchRequest = """[
            {"jsonrpc":"2.0","id":1,"method":"ping"},
            {"jsonrpc":"2.0","id":2,"method":"tools/list"}
        ]"""

        val response = client.post("http://localhost:$port/mcp") {
            contentType(ContentType.Application.Json)
            accept(ContentType.Application.Json)
            header(McpHttpTransport.SESSION_HEADER, sessionId)
            setBody(batchRequest)
        }

        assertEquals(HttpStatusCode.OK, response.status)

        val body = response.bodyAsText()
        val responses = McpJson.decodeFromString<JsonArray>(body)
        assertEquals(2, responses.size)
    }

    // ==================== GET Request Tests ====================

    @Test
    fun `test GET with Accept text event-stream returns 405 Method Not Allowed`() = runBlocking {
        // Per MCP spec, client MUST include Accept: text/event-stream
        // Server returns 405 if it doesn't support SSE
        val response = client.get("http://localhost:$port/mcp") {
            header(HttpHeaders.Accept, "text/event-stream")
        }

        // The response should complete immediately with 405 (SSE not supported)
        assertEquals(HttpStatusCode.MethodNotAllowed, response.status)
    }

    @Test
    fun `test GET without Accept header returns server info`() = runBlocking {
        // When no Accept header is provided, HTTP default is */*, accept anything
        // Ktor client adds Accept: */* by default
        // Server returns server info for availability checks
        val response = client.get("http://localhost:$port/mcp")

        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(response.bodyAsText().contains("intellij-mcp-steroid"))
    }

    @Test
    fun `test GET with application json Accept header returns server info`() = runBlocking {
        // GET with Accept: application/json returns server info for availability checks
        val response = client.get("http://localhost:$port/mcp") {
            header(HttpHeaders.Accept, "application/json")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(response.bodyAsText().contains("intellij-mcp-steroid"))
    }

    @Test
    fun `test GET with wildcard Accept returns server info`() = runBlocking {
        // Wildcard Accept (*/*) returns server info for availability checks
        val response = client.get("http://localhost:$port/mcp") {
            header(HttpHeaders.Accept, "*/*")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(response.bodyAsText().contains("intellij-mcp-steroid"))
    }

    @Test
    fun `test GET with combined Accept header returns server info`() = runBlocking {
        // MCP clients (like Claude CLI) send: Accept: application/json, text/event-stream
        // When JSON is acceptable, return server info for availability checks
        val response = client.get("http://localhost:$port/mcp") {
            header(HttpHeaders.Accept, "application/json, text/event-stream")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(response.bodyAsText().contains("intellij-mcp-steroid"))
    }

    // ==================== Unknown Method Tests ====================

    @Test
    fun `test POST unknown method returns METHOD_NOT_FOUND error`() = runBlocking {
        // First initialize to get session
        val initRequest = buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", 1)
            put("method", "initialize")
            putJsonObject("params") {
                put("protocolVersion", MCP_PROTOCOL_VERSION)
                putJsonObject("capabilities") {}
                putJsonObject("clientInfo") {
                    put("name", "test-client")
                    put("version", "1.0.0")
                }
            }
        }

        val initResponse = client.post("http://localhost:$port/mcp") {
            contentType(ContentType.Application.Json)
            accept(ContentType.Application.Json)
            setBody(initRequest.toString())
        }
        val sessionId = initResponse.headers[McpHttpTransport.SESSION_HEADER]

        // Call unknown method
        val unknownRequest = """{"jsonrpc":"2.0","id":2,"method":"unknown/method"}"""

        val response = client.post("http://localhost:$port/mcp") {
            contentType(ContentType.Application.Json)
            accept(ContentType.Application.Json)
            header(McpHttpTransport.SESSION_HEADER, sessionId)
            setBody(unknownRequest)
        }

        assertEquals(HttpStatusCode.OK, response.status)

        val body = response.bodyAsText()
        val jsonResponse = McpJson.decodeFromString<JsonRpcResponse>(body)

        assertNotNull("Should have error", jsonResponse.error)
        assertEquals(JsonRpcErrorCodes.METHOD_NOT_FOUND, jsonResponse.error?.code)
        assertTrue(
            "Error message should mention method",
            jsonResponse.error?.message?.contains("not found") == true
        )
    }

    @Test
    fun `test POST prompts list returns METHOD_NOT_FOUND`() = runBlocking {
        val initRequest = buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", 1)
            put("method", "initialize")
            putJsonObject("params") {
                put("protocolVersion", MCP_PROTOCOL_VERSION)
                putJsonObject("capabilities") {}
                putJsonObject("clientInfo") {
                    put("name", "test-client")
                    put("version", "1.0.0")
                }
            }
        }

        val initResponse = client.post("http://localhost:$port/mcp") {
            contentType(ContentType.Application.Json)
            accept(ContentType.Application.Json)
            setBody(initRequest.toString())
        }
        val sessionId = initResponse.headers[McpHttpTransport.SESSION_HEADER]

        // Call prompts/list which is not implemented
        val promptsRequest = """{"jsonrpc":"2.0","id":2,"method":"prompts/list"}"""

        val response = client.post("http://localhost:$port/mcp") {
            contentType(ContentType.Application.Json)
            accept(ContentType.Application.Json)
            header(McpHttpTransport.SESSION_HEADER, sessionId)
            setBody(promptsRequest)
        }

        assertEquals(HttpStatusCode.OK, response.status)

        val body = response.bodyAsText()
        val jsonResponse = McpJson.decodeFromString<JsonRpcResponse>(body)

        assertNotNull("Should have error for unimplemented method", jsonResponse.error)
        assertEquals(JsonRpcErrorCodes.METHOD_NOT_FOUND, jsonResponse.error?.code)
    }

    @Test
    fun `test POST resources list returns METHOD_NOT_FOUND`() = runBlocking {
        val initRequest = buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", 1)
            put("method", "initialize")
            putJsonObject("params") {
                put("protocolVersion", MCP_PROTOCOL_VERSION)
                putJsonObject("capabilities") {}
                putJsonObject("clientInfo") {
                    put("name", "test-client")
                    put("version", "1.0.0")
                }
            }
        }

        val initResponse = client.post("http://localhost:$port/mcp") {
            contentType(ContentType.Application.Json)
            accept(ContentType.Application.Json)
            setBody(initRequest.toString())
        }
        val sessionId = initResponse.headers[McpHttpTransport.SESSION_HEADER]

        // Call resources/list which is not implemented
        val resourcesRequest = """{"jsonrpc":"2.0","id":2,"method":"resources/list"}"""

        val response = client.post("http://localhost:$port/mcp") {
            contentType(ContentType.Application.Json)
            accept(ContentType.Application.Json)
            header(McpHttpTransport.SESSION_HEADER, sessionId)
            setBody(resourcesRequest)
        }

        assertEquals(HttpStatusCode.OK, response.status)

        val body = response.bodyAsText()
        val jsonResponse = McpJson.decodeFromString<JsonRpcResponse>(body)

        assertNotNull("Should have error for unimplemented method", jsonResponse.error)
        assertEquals(JsonRpcErrorCodes.METHOD_NOT_FOUND, jsonResponse.error?.code)
    }

    @Test
    fun `test POST tools call with unknown tool returns error in result`() = runBlocking {
        val initRequest = buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", 1)
            put("method", "initialize")
            putJsonObject("params") {
                put("protocolVersion", MCP_PROTOCOL_VERSION)
                putJsonObject("capabilities") {}
                putJsonObject("clientInfo") {
                    put("name", "test-client")
                    put("version", "1.0.0")
                }
            }
        }

        val initResponse = client.post("http://localhost:$port/mcp") {
            contentType(ContentType.Application.Json)
            accept(ContentType.Application.Json)
            setBody(initRequest.toString())
        }
        val sessionId = initResponse.headers[McpHttpTransport.SESSION_HEADER]

        // Call unknown tool
        val callRequest = buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", 2)
            put("method", "tools/call")
            putJsonObject("params") {
                put("name", "unknown_tool_that_does_not_exist")
            }
        }

        val response = client.post("http://localhost:$port/mcp") {
            contentType(ContentType.Application.Json)
            accept(ContentType.Application.Json)
            header(McpHttpTransport.SESSION_HEADER, sessionId)
            setBody(callRequest.toString())
        }

        assertEquals(HttpStatusCode.OK, response.status)

        val body = response.bodyAsText()
        val jsonResponse = McpJson.decodeFromString<JsonRpcResponse>(body)

        // tools/call returns result with isError=true, not a JSON-RPC error
        assertNull("Should not have JSON-RPC error", jsonResponse.error)
        assertNotNull("Should have result", jsonResponse.result)

        val result = McpJson.decodeFromJsonElement<ToolCallResult>(jsonResponse.result!!)
        assertTrue("Should be marked as error", result.isError)
        assertTrue(
            "Error content should mention tool not found",
            (result.content[0] as ContentItem.Text).text.contains("not found")
        )
    }

    @Test
    fun `test POST malformed JSON returns PARSE_ERROR`() = runBlocking {
        val response = client.post("http://localhost:$port/mcp") {
            contentType(ContentType.Application.Json)
            accept(ContentType.Application.Json)
            setBody("{ invalid json }")
        }

        assertEquals(HttpStatusCode.OK, response.status)

        val body = response.bodyAsText()
        val jsonResponse = McpJson.decodeFromString<JsonRpcResponse>(body)

        assertNotNull("Should have error", jsonResponse.error)
        assertEquals(JsonRpcErrorCodes.PARSE_ERROR, jsonResponse.error?.code)
    }

    @Test
    fun `test POST missing method returns INVALID_REQUEST`() = runBlocking {
        val initRequest = buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", 1)
            put("method", "initialize")
            putJsonObject("params") {
                put("protocolVersion", MCP_PROTOCOL_VERSION)
                putJsonObject("capabilities") {}
                putJsonObject("clientInfo") {
                    put("name", "test-client")
                    put("version", "1.0.0")
                }
            }
        }

        val initResponse = client.post("http://localhost:$port/mcp") {
            contentType(ContentType.Application.Json)
            accept(ContentType.Application.Json)
            setBody(initRequest.toString())
        }
        val sessionId = initResponse.headers[McpHttpTransport.SESSION_HEADER]

        // Request without method field
        val invalidRequest = """{"jsonrpc":"2.0","id":2}"""

        val response = client.post("http://localhost:$port/mcp") {
            contentType(ContentType.Application.Json)
            accept(ContentType.Application.Json)
            header(McpHttpTransport.SESSION_HEADER, sessionId)
            setBody(invalidRequest)
        }

        assertEquals(HttpStatusCode.OK, response.status)

        val body = response.bodyAsText()
        val jsonResponse = McpJson.decodeFromString<JsonRpcResponse>(body)

        assertNotNull("Should have error", jsonResponse.error)
        assertEquals(JsonRpcErrorCodes.INVALID_REQUEST, jsonResponse.error?.code)
    }

    @Test
    fun `test POST batch with unknown methods returns errors for each`() = runBlocking {
        val initRequest = buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", 1)
            put("method", "initialize")
            putJsonObject("params") {
                put("protocolVersion", MCP_PROTOCOL_VERSION)
                putJsonObject("capabilities") {}
                putJsonObject("clientInfo") {
                    put("name", "test-client")
                    put("version", "1.0.0")
                }
            }
        }

        val initResponse = client.post("http://localhost:$port/mcp") {
            contentType(ContentType.Application.Json)
            accept(ContentType.Application.Json)
            setBody(initRequest.toString())
        }
        val sessionId = initResponse.headers[McpHttpTransport.SESSION_HEADER]

        // Batch with known and unknown methods
        val batchRequest = """[
            {"jsonrpc":"2.0","id":1,"method":"ping"},
            {"jsonrpc":"2.0","id":2,"method":"unknown/method1"},
            {"jsonrpc":"2.0","id":3,"method":"tools/list"},
            {"jsonrpc":"2.0","id":4,"method":"unknown/method2"}
        ]"""

        val response = client.post("http://localhost:$port/mcp") {
            contentType(ContentType.Application.Json)
            accept(ContentType.Application.Json)
            header(McpHttpTransport.SESSION_HEADER, sessionId)
            setBody(batchRequest)
        }

        assertEquals(HttpStatusCode.OK, response.status)

        val body = response.bodyAsText()
        val responses = McpJson.decodeFromString<JsonArray>(body)
        assertEquals(4, responses.size)

        // Check that unknown methods return errors
        val response1 = McpJson.decodeFromJsonElement<JsonRpcResponse>(responses[0])
        val response2 = McpJson.decodeFromJsonElement<JsonRpcResponse>(responses[1])
        val response3 = McpJson.decodeFromJsonElement<JsonRpcResponse>(responses[2])
        val response4 = McpJson.decodeFromJsonElement<JsonRpcResponse>(responses[3])

        assertNull("ping should succeed", response1.error)
        assertNotNull("unknown/method1 should fail", response2.error)
        assertEquals(JsonRpcErrorCodes.METHOD_NOT_FOUND, response2.error?.code)
        assertNull("tools/list should succeed", response3.error)
        assertNotNull("unknown/method2 should fail", response4.error)
        assertEquals(JsonRpcErrorCodes.METHOD_NOT_FOUND, response4.error?.code)
    }

    // ==================== Header Validation Tests ====================

    @Test
    fun `test POST without Content-Type returns UnsupportedMediaType`() = runBlocking {
        val request = """{"jsonrpc":"2.0","id":1,"method":"initialize"}"""

        val response = client.post("http://localhost:$port/mcp") {
            accept(ContentType.Application.Json)
            setBody(request)
        }

        assertEquals(HttpStatusCode.UnsupportedMediaType, response.status)
    }

    @Test
    fun `test POST with wrong Content-Type returns UnsupportedMediaType`() = runBlocking {
        val request = """{"jsonrpc":"2.0","id":1,"method":"initialize"}"""

        val response = client.post("http://localhost:$port/mcp") {
            contentType(ContentType.Text.Plain)
            accept(ContentType.Application.Json)
            setBody(request)
        }

        assertEquals(HttpStatusCode.UnsupportedMediaType, response.status)
    }

    @Test
    fun `test POST with Accept header not including json returns NotAcceptable`() = runBlocking {
        val request = """{"jsonrpc":"2.0","id":1,"method":"initialize"}"""

        val response = client.post("http://localhost:$port/mcp") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Accept, "text/html")
            setBody(request)
        }

        assertEquals(HttpStatusCode.NotAcceptable, response.status)
    }

    @Test
    fun `test POST with Accept wildcard succeeds`() = runBlocking {
        val request = buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", 1)
            put("method", "initialize")
            putJsonObject("params") {
                put("protocolVersion", MCP_PROTOCOL_VERSION)
                putJsonObject("capabilities") {}
                putJsonObject("clientInfo") {
                    put("name", "test-client")
                    put("version", "1.0.0")
                }
            }
        }

        val response = client.post("http://localhost:$port/mcp") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Accept, "*/*")
            setBody(request.toString())
        }

        assertEquals(HttpStatusCode.OK, response.status)
    }

    @Test
    fun `test POST with Accept application wildcard succeeds`() = runBlocking {
        val request = buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", 1)
            put("method", "initialize")
            putJsonObject("params") {
                put("protocolVersion", MCP_PROTOCOL_VERSION)
                putJsonObject("capabilities") {}
                putJsonObject("clientInfo") {
                    put("name", "test-client")
                    put("version", "1.0.0")
                }
            }
        }

        val response = client.post("http://localhost:$port/mcp") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Accept, "application/*")
            setBody(request.toString())
        }

        assertEquals(HttpStatusCode.OK, response.status)
    }

    @Test
    fun `test POST with combined Accept header succeeds`() = runBlocking {
        // MCP clients typically send: Accept: application/json, text/event-stream
        val request = buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", 1)
            put("method", "initialize")
            putJsonObject("params") {
                put("protocolVersion", MCP_PROTOCOL_VERSION)
                putJsonObject("capabilities") {}
                putJsonObject("clientInfo") {
                    put("name", "test-client")
                    put("version", "1.0.0")
                }
            }
        }

        val response = client.post("http://localhost:$port/mcp") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Accept, "application/json, text/event-stream")
            setBody(request.toString())
        }

        assertEquals(HttpStatusCode.OK, response.status)
    }

    @Test
    fun `test POST without Accept header succeeds`() = runBlocking {
        // Accept header is optional - if not provided, we assume the client accepts any response
        val request = buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", 1)
            put("method", "initialize")
            putJsonObject("params") {
                put("protocolVersion", MCP_PROTOCOL_VERSION)
                putJsonObject("capabilities") {}
                putJsonObject("clientInfo") {
                    put("name", "test-client")
                    put("version", "1.0.0")
                }
            }
        }

        val response = client.post("http://localhost:$port/mcp") {
            contentType(ContentType.Application.Json)
            // No Accept header
            setBody(request.toString())
        }

        assertEquals(HttpStatusCode.OK, response.status)
    }
}
