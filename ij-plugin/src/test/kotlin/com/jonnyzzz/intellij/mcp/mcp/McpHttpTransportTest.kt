/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.intellij.mcp.mcp

import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.application.*
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
        ) { params, _ ->
            val message = params.arguments?.get("message")?.jsonPrimitive?.content ?: ""
            ToolCallResult(content = listOf(ContentItem.Text(text = "Echo: $message")))
        }

        server = embeddedServer(io.ktor.server.cio.CIO, port = port) {
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
            setBody(initRequest.toString())
        }

        val sessionId = firstResponse.headers[McpHttpTransport.SESSION_HEADER]
        assertNotNull(sessionId)

        // Second request with session ID
        val pingRequest = """{"jsonrpc":"2.0","id":2,"method":"ping"}"""

        val secondResponse = client.post("http://localhost:$port/mcp") {
            contentType(ContentType.Application.Json)
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
            setBody(initRequest.toString())
        }
        val sessionId = initResponse.headers[McpHttpTransport.SESSION_HEADER]

        // Then list tools
        val listRequest = """{"jsonrpc":"2.0","id":2,"method":"tools/list"}"""

        val response = client.post("http://localhost:$port/mcp") {
            contentType(ContentType.Application.Json)
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
            setBody(initRequest.toString())
        }
        val sessionId = initResponse.headers[McpHttpTransport.SESSION_HEADER]

        // Send notification (no id)
        val notification = """{"jsonrpc":"2.0","method":"notifications/initialized"}"""

        val response = client.post("http://localhost:$port/mcp") {
            contentType(ContentType.Application.Json)
            header(McpHttpTransport.SESSION_HEADER, sessionId)
            setBody(notification)
        }

        assertEquals(HttpStatusCode.Accepted, response.status)
    }

    @Test
    fun `test POST empty body returns BadRequest`() = runBlocking {
        val response = client.post("http://localhost:$port/mcp") {
            contentType(ContentType.Application.Json)
            setBody("")
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `test POST with invalid session returns BadRequest`() = runBlocking {
        val request = """{"jsonrpc":"2.0","id":1,"method":"ping"}"""

        val response = client.post("http://localhost:$port/mcp") {
            contentType(ContentType.Application.Json)
            header(McpHttpTransport.SESSION_HEADER, "invalid-session-id")
            setBody(request)
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
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
            setBody(initRequest.toString())
        }
        val sessionId = initResponse.headers[McpHttpTransport.SESSION_HEADER]

        // Delete session
        val deleteResponse = client.delete("http://localhost:$port/mcp") {
            header(McpHttpTransport.SESSION_HEADER, sessionId)
        }

        assertEquals(HttpStatusCode.NoContent, deleteResponse.status)

        // Verify session is gone - next request with same session should fail
        val pingRequest = """{"jsonrpc":"2.0","id":2,"method":"ping"}"""
        val afterDeleteResponse = client.post("http://localhost:$port/mcp") {
            contentType(ContentType.Application.Json)
            header(McpHttpTransport.SESSION_HEADER, sessionId)
            setBody(pingRequest)
        }

        assertEquals(HttpStatusCode.BadRequest, afterDeleteResponse.status)
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
            header(McpHttpTransport.SESSION_HEADER, sessionId)
            setBody(batchRequest)
        }

        assertEquals(HttpStatusCode.OK, response.status)

        val body = response.bodyAsText()
        val responses = McpJson.decodeFromString<JsonArray>(body)
        assertEquals(2, responses.size)
    }
}
