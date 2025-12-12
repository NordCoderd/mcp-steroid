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
}
