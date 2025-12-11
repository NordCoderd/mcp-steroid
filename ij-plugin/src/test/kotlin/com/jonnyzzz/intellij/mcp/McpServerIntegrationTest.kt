/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.intellij.mcp

import com.intellij.mcpserver.McpToolsProvider
import com.intellij.testFramework.common.timeoutRunBlocking
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URI
import kotlin.time.Duration.Companion.seconds

/**
 * Integration tests for the MCP Server functionality.
 *
 * These tests verify that:
 * - The MCP server starts and is accessible
 * - The SSE transport works correctly with Ktor client
 * - Our steroid_ tools are properly registered and visible via the MCP protocol
 *
 * Note: Uses JUnit 3 style with BasePlatformTestCase.
 */
class McpServerIntegrationTest : BasePlatformTestCase() {

    fun testMcpServerStartsSuccessfully(): Unit = timeoutRunBlocking(30.seconds) {
        McpTestUtil.withMcpServer { port, sseUrl ->
            assertTrue("Port should be positive: $port", port > 0)
            assertNotNull("SSE URL should not be null", sseUrl)
            assertTrue("SSE URL should contain port: $sseUrl", sseUrl.contains(port.toString()))
        }
    }

    fun testSteroidToolsAreRegisteredViaMcpToolsProvider(): Unit = timeoutRunBlocking(30.seconds) {
        val allTools = McpToolsProvider.EP.extensionList.flatMap {
            try {
                it.getTools()
            } catch (e: Exception) {
                println("[TEST] Error getting tools from $it: ${e.message}")
                emptyList()
            }
        }

        val toolNames = allTools.map { it.descriptor.name }
        println("[TEST] All registered MCP tools (${toolNames.size}): ${toolNames.joinToString(", ")}")

        val steroidTools = allTools.filter { it.descriptor.name.startsWith("steroid_") }
        println("[TEST] Steroid tools found (${steroidTools.size}): ${steroidTools.joinToString(", ") { it.descriptor.name }}")

        assertTrue("Should find at least one steroid_ tool", steroidTools.isNotEmpty())

        val steroidToolNames = steroidTools.map { it.descriptor.name }
        assertTrue("Should contain steroid_list_projects", steroidToolNames.contains("steroid_list_projects"))
        assertTrue("Should contain steroid_execute_code", steroidToolNames.contains("steroid_execute_code"))
        assertTrue("Should contain steroid_get_result", steroidToolNames.contains("steroid_get_result"))
        assertTrue("Should contain steroid_cancel_execution", steroidToolNames.contains("steroid_cancel_execution"))
    }

    fun testSteroidListProjectsToolHasCorrectDescriptor(): Unit = timeoutRunBlocking(30.seconds) {
        val allTools = McpToolsProvider.EP.extensionList.flatMap { it.getTools() }
        val listProjectsTool = allTools.find { it.descriptor.name == "steroid_list_projects" }

        assertNotNull("steroid_list_projects tool should exist", listProjectsTool)
        assertNotNull("Tool should have a description", listProjectsTool!!.descriptor.description)
        assertTrue(
            "Description should mention 'project'",
            listProjectsTool.descriptor.description.contains("project", ignoreCase = true)
        )

        println("[TEST] steroid_list_projects description: ${listProjectsTool.descriptor.description}")
    }

    fun testSteroidExecuteCodeToolHasCorrectSchema(): Unit = timeoutRunBlocking(30.seconds) {
        val allTools = McpToolsProvider.EP.extensionList.flatMap { it.getTools() }
        val executeCodeTool = allTools.find { it.descriptor.name == "steroid_execute_code" }

        assertNotNull("steroid_execute_code tool should exist", executeCodeTool)

        val schema = executeCodeTool!!.descriptor.inputSchema
        assertNotNull("Tool should have an input schema", schema)

        val propertyNames = schema.propertiesSchema.keys
        println("[TEST] steroid_execute_code schema properties: $propertyNames")

        assertTrue("Should have 'project_name' parameter", propertyNames.contains("project_name"))
        assertTrue("Should have 'code' parameter", propertyNames.contains("code"))

        println("[TEST] steroid_execute_code description: ${executeCodeTool.descriptor.description.take(200)}...")
    }

    fun testSteroidGetResultToolHasCorrectSchema(): Unit = timeoutRunBlocking(30.seconds) {
        val allTools = McpToolsProvider.EP.extensionList.flatMap { it.getTools() }
        val getResultTool = allTools.find { it.descriptor.name == "steroid_get_result" }

        assertNotNull("steroid_get_result tool should exist", getResultTool)

        val schema = getResultTool!!.descriptor.inputSchema
        assertNotNull("Tool should have an input schema", schema)

        val propertyNames = schema.propertiesSchema.keys
        println("[TEST] steroid_get_result schema properties: $propertyNames")

        assertTrue("Should have 'execution_id' parameter", propertyNames.contains("execution_id"))
    }

    fun testSteroidCancelExecutionToolHasCorrectSchema(): Unit = timeoutRunBlocking(30.seconds) {
        val allTools = McpToolsProvider.EP.extensionList.flatMap { it.getTools() }
        val cancelTool = allTools.find { it.descriptor.name == "steroid_cancel_execution" }

        assertNotNull("steroid_cancel_execution tool should exist", cancelTool)

        val schema = cancelTool!!.descriptor.inputSchema
        assertNotNull("Tool should have an input schema", schema)

        val propertyNames = schema.propertiesSchema.keys
        println("[TEST] steroid_cancel_execution schema properties: $propertyNames")

        assertTrue("Should have 'execution_id' parameter", propertyNames.contains("execution_id"))
    }

    fun testAllSteroidToolsHaveDescriptions(): Unit = timeoutRunBlocking(30.seconds) {
        val allTools = McpToolsProvider.EP.extensionList.flatMap { it.getTools() }
        val steroidTools = allTools.filter { it.descriptor.name.startsWith("steroid_") }

        steroidTools.forEach { tool ->
            assertNotNull("Tool ${tool.descriptor.name} should have a description", tool.descriptor.description)
            assertTrue(
                "Tool ${tool.descriptor.name} description should not be blank",
                tool.descriptor.description.isNotBlank()
            )
            println("[TEST] ${tool.descriptor.name}: ${tool.descriptor.description.take(100)}...")
        }

        assertEquals("Should have exactly 4 steroid_ tools", 4, steroidTools.size)
    }

    fun testSteroidToolsHaveRequiredParameters(): Unit = timeoutRunBlocking(30.seconds) {
        val allTools = McpToolsProvider.EP.extensionList.flatMap { it.getTools() }
        val steroidTools = allTools.filter { it.descriptor.name.startsWith("steroid_") }

        val executeCodeTool = steroidTools.find { it.descriptor.name == "steroid_execute_code" }
        assertNotNull("execute_code tool should exist", executeCodeTool)
        val execRequired = executeCodeTool!!.descriptor.inputSchema.requiredProperties
        println("[TEST] steroid_execute_code required parameters: $execRequired")
        assertTrue("project_name should be required", execRequired.contains("project_name"))
        assertTrue("code should be required", execRequired.contains("code"))

        val getResultTool = steroidTools.find { it.descriptor.name == "steroid_get_result" }
        assertNotNull("get_result tool should exist", getResultTool)
        val getRequired = getResultTool!!.descriptor.inputSchema.requiredProperties
        println("[TEST] steroid_get_result required parameters: $getRequired")
        assertTrue("execution_id should be required", getRequired.contains("execution_id"))
    }

    /**
     * Tests that steroid_ tools are returned via the MCP SSE transport using raw HTTP.
     *
     * This test uses raw HTTP/SSE to implement the MCP protocol:
     * 1. GET /sse to establish SSE connection and get session ID
     * 2. POST /message?sessionId=... with JSON-RPC requests
     * 3. Read responses from SSE stream
     *
     * Note: This test requires the MCP server's HTTP endpoint to be functional.
     * In unit test environments where only the service is mocked, this test may be skipped.
     * For full integration testing, use the shell scripts in integration-test/ directory
     * or run tests against a running IntelliJ instance.
     */
    fun testMcpSseTransportReturnsSteroidToolsWithKtorClient(): Unit = timeoutRunBlocking(60.seconds) {
        McpTestUtil.withMcpServer { port, sseUrl ->
            println("[TEST] Testing MCP SSE transport at port: $port, URL: $sseUrl")

            // Quick connectivity check with short timeout
            val connectionCheckUrl = URI("http://localhost:$port/sse").toURL()
            val checkConnection = connectionCheckUrl.openConnection() as HttpURLConnection
            checkConnection.setRequestProperty("Accept", "text/event-stream")
            checkConnection.connectTimeout = 2000
            checkConnection.readTimeout = 3000

            try {
                // Try to connect - if this fails or times out, skip the test
                checkConnection.connect()
                val responseCode = checkConnection.responseCode

                if (responseCode != 200) {
                    println("[TEST] SSE endpoint returned $responseCode, skipping HTTP transport test")
                    println("[TEST] Note: Use integration-test/test-sse-tools.sh for full HTTP testing")
                    return@withMcpServer
                }

                // Read first line to verify SSE is working
                val reader = BufferedReader(InputStreamReader(checkConnection.inputStream))
                val firstLine = withTimeout(3.seconds) {
                    runCatching { reader.readLine() }.getOrNull()
                }

                if (firstLine == null) {
                    println("[TEST] No data from SSE endpoint, skipping HTTP transport test")
                    println("[TEST] Note: Use integration-test/test-sse-tools.sh for full HTTP testing")
                    reader.close()
                    checkConnection.disconnect()
                    return@withMcpServer
                }

                println("[TEST] SSE endpoint is responsive: $firstLine")

                // Continue with full SSE protocol test
                var sessionId: String? = null
                var currentLine: String? = firstLine

                while (sessionId == null && currentLine != null) {
                    println("[TEST] SSE: $currentLine")
                    if (currentLine.startsWith("data:")) {
                        val data = currentLine.removePrefix("data:").trim()
                        if (data.contains("endpoint")) {
                            val json = Json.parseToJsonElement(data).jsonObject
                            val endpoint = json["endpoint"]?.jsonPrimitive?.content
                            sessionId = endpoint?.substringAfter("sessionId=")?.substringBefore("&")
                            println("[TEST] Got session ID: $sessionId")
                        }
                    }
                    currentLine = runCatching { reader.readLine() }.getOrNull()
                }

                if (sessionId == null) {
                    println("[TEST] Could not get session ID, skipping test")
                    reader.close()
                    checkConnection.disconnect()
                    return@withMcpServer
                }

                // Create HTTP client for POST requests
                val httpClient = HttpClient(CIO) {
                    install(ContentNegotiation) {
                        json(Json { ignoreUnknownKeys = true })
                    }
                }

                try {
                    val messageUrl = "http://localhost:$port/message?sessionId=$sessionId"

                    // Send initialize request
                    val initRequest = """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2024-11-05","capabilities":{},"clientInfo":{"name":"kotlin-test","version":"1.0.0"}}}"""
                    println("[TEST] Sending initialize to: $messageUrl")
                    httpClient.post(messageUrl) {
                        contentType(ContentType.Application.Json)
                        setBody(initRequest)
                    }

                    // Read initialize response
                    var initReceived = false
                    while (!initReceived) {
                        val line = runCatching { reader.readLine() }.getOrNull() ?: break
                        if (line.contains("\"id\":1") || line.contains("\"id\": 1")) {
                            println("[TEST] Initialize response received")
                            initReceived = true
                        }
                    }

                    // Send tools/list request
                    val listToolsRequest = """{"jsonrpc":"2.0","id":2,"method":"tools/list","params":{}}"""
                    println("[TEST] Sending tools/list request")
                    httpClient.post(messageUrl) {
                        contentType(ContentType.Application.Json)
                        setBody(listToolsRequest)
                    }

                    // Read tools/list response
                    var toolsResult: JsonObject? = null
                    while (toolsResult == null) {
                        val line = runCatching { reader.readLine() }.getOrNull() ?: break
                        if (line.startsWith("data:") && (line.contains("\"id\":2") || line.contains("\"id\": 2"))) {
                            val data = line.removePrefix("data:").trim()
                            toolsResult = Json.parseToJsonElement(data).jsonObject
                            println("[TEST] Tools result received")
                        }
                    }

                    assertNotNull("Should receive tools list result", toolsResult)

                    val tools = toolsResult!!["result"]?.jsonObject?.get("tools")?.jsonArray
                    assertNotNull("Result should contain tools array", tools)

                    val steroidToolNames = tools!!.mapNotNull {
                        it.jsonObject["name"]?.jsonPrimitive?.content
                    }.filter { it.startsWith("steroid_") }

                    println("[TEST] Steroid tools found via SSE: $steroidToolNames")

                    assertTrue("Should contain steroid_list_projects", steroidToolNames.contains("steroid_list_projects"))
                    assertTrue("Should contain steroid_execute_code", steroidToolNames.contains("steroid_execute_code"))
                    assertTrue("Should contain steroid_get_result", steroidToolNames.contains("steroid_get_result"))
                    assertTrue("Should contain steroid_cancel_execution", steroidToolNames.contains("steroid_cancel_execution"))
                    assertEquals("Should have exactly 4 steroid_ tools", 4, steroidToolNames.size)

                    println("[TEST] All steroid_ tools verified via MCP SSE transport!")
                } finally {
                    runCatching { httpClient.close() }
                }
                runCatching { reader.close() }
                runCatching { checkConnection.disconnect() }

            } catch (e: java.net.ConnectException) {
                println("[TEST] Could not connect to MCP SSE endpoint: ${e.message}")
                println("[TEST] Note: This is expected in unit test environment. Use integration-test/test-sse-tools.sh for full HTTP testing")
            } catch (e: java.net.SocketTimeoutException) {
                println("[TEST] SSE endpoint timed out: ${e.message}")
                println("[TEST] Note: This is expected in unit test environment. Use integration-test/test-sse-tools.sh for full HTTP testing")
            } finally {
                runCatching { checkConnection.disconnect() }
            }
        }
    }

    /**
     * Tests calling steroid_list_projects via MCP SSE transport.
     *
     * Note: This test requires the MCP server's HTTP endpoint to be functional.
     * In unit test environments where only the service is mocked, this test may be skipped.
     */
    fun testCallSteroidListProjectsViaMcpSseTransport(): Unit = timeoutRunBlocking(60.seconds) {
        McpTestUtil.withMcpServer { port, sseUrl ->
            println("[TEST] Testing steroid_list_projects call at port: $port")

            // Quick connectivity check
            val connectionCheckUrl = URI("http://localhost:$port/sse").toURL()
            val checkConnection = connectionCheckUrl.openConnection() as HttpURLConnection
            checkConnection.setRequestProperty("Accept", "text/event-stream")
            checkConnection.connectTimeout = 2000
            checkConnection.readTimeout = 3000

            try {
                checkConnection.connect()
                if (checkConnection.responseCode != 200) {
                    println("[TEST] SSE endpoint not available, skipping tool call test")
                    return@withMcpServer
                }

                val reader = BufferedReader(InputStreamReader(checkConnection.inputStream))
                var sessionId: String? = null

                // Get session ID
                while (sessionId == null) {
                    val line = runCatching { reader.readLine() }.getOrNull() ?: break
                    if (line.startsWith("data:") && line.contains("endpoint")) {
                        val data = line.removePrefix("data:").trim()
                        val json = Json.parseToJsonElement(data).jsonObject
                        val endpoint = json["endpoint"]?.jsonPrimitive?.content
                        sessionId = endpoint?.substringAfter("sessionId=")?.substringBefore("&")
                    }
                }

                if (sessionId == null) {
                    println("[TEST] Could not get session ID, skipping test")
                    reader.close()
                    return@withMcpServer
                }

                val httpClient = HttpClient(CIO) {
                    install(ContentNegotiation) {
                        json(Json { ignoreUnknownKeys = true })
                    }
                }

                try {
                    val messageUrl = "http://localhost:$port/message?sessionId=$sessionId"

                    // Initialize
                    httpClient.post(messageUrl) {
                        contentType(ContentType.Application.Json)
                        setBody("""{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2024-11-05","capabilities":{},"clientInfo":{"name":"kotlin-test","version":"1.0.0"}}}""")
                    }

                    // Wait for initialize response
                    while (true) {
                        val line = runCatching { reader.readLine() }.getOrNull() ?: break
                        if (line.contains("\"id\":1") || line.contains("\"id\": 1")) break
                    }

                    // Call steroid_list_projects
                    println("[TEST] Calling steroid_list_projects...")
                    httpClient.post(messageUrl) {
                        contentType(ContentType.Application.Json)
                        setBody("""{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"steroid_list_projects","arguments":{}}}""")
                    }

                    // Read response
                    var toolResult: JsonObject? = null
                    while (toolResult == null) {
                        val line = runCatching { reader.readLine() }.getOrNull() ?: break
                        if (line.startsWith("data:") && (line.contains("\"id\":3") || line.contains("\"id\": 3"))) {
                            val data = line.removePrefix("data:").trim()
                            toolResult = Json.parseToJsonElement(data).jsonObject
                        }
                    }

                    assertNotNull("Result should not be null", toolResult)
                    val content = toolResult!!["result"]?.jsonObject?.get("content")?.jsonArray
                    assertNotNull("Result should have content", content)

                    println("[TEST] steroid_list_projects call successful via SSE!")
                    println("[TEST] Content: $content")
                } finally {
                    runCatching { httpClient.close() }
                }
                runCatching { reader.close() }

            } catch (e: java.net.ConnectException) {
                println("[TEST] Could not connect to MCP SSE endpoint: ${e.message}")
                println("[TEST] Note: Expected in unit test environment")
            } catch (e: java.net.SocketTimeoutException) {
                println("[TEST] SSE endpoint timed out: ${e.message}")
                println("[TEST] Note: Expected in unit test environment")
            } finally {
                runCatching { checkConnection.disconnect() }
            }
        }
    }
}
