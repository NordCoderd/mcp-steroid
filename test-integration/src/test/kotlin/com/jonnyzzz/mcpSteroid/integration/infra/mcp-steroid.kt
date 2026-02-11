/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.integration.infra

import com.jonnyzzz.mcpSteroid.testHelper.ProcessResult
import com.jonnyzzz.mcpSteroid.testHelper.ProcessResultValue
import com.jonnyzzz.mcpSteroid.testHelper.docker.ContainerDriver
import com.jonnyzzz.mcpSteroid.testHelper.docker.ContainerPort
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

fun McpSteroidDriver.waitForIndexesReady() {
    TODO("Not yet implemented")
}

class McpSteroidDriver(
    private val driver: ContainerDriver,
) {
    companion object {
        val MCP_STEROID_PORT = ContainerPort(6754)
    }

    private val json = Json { prettyPrint = true }

    val guestMcpUrl = "http://localhost:${MCP_STEROID_PORT.containerPort}/mcp"
    val hostMcpUrl get() = "http://localhost:${driver.mapGuestPortToHostPort(MCP_STEROID_PORT)}/mcp"



    fun waitForMcpReady() {
        //TODO: reuse code with code in this file

        // First wait for the server to be reachable via a simple GET health check.
        // This avoids creating orphan sessions from repeated initialize requests.
        waitFor(300_000, "Wait for MCP Steroid ready") {
            val result = driver.runInContainer(
                listOf(
                    "curl", "-s", "-f",
                    guestMcpUrl,
                    "-H", "Accept: application/json",
                ),
                timeoutSeconds = 5,
            )
            result.exitCode == 0
        }

        // Verify the MCP protocol works with a proper initialize handshake
        val mcpInit = """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-11-25","capabilities":{},"clientInfo":{"name":"integration-test","version":"1.0"}}}"""
        val result = driver.runInContainer(
            listOf(
                "curl", "-s", "-f", "-X", "POST",
                guestMcpUrl,
                "-H", "Content-Type: application/json",
                "-H", "Accept: application/json",
                "-d", mcpInit,
            ),
            timeoutSeconds = 10,
        )
        check(result.exitCode == 0) {
            "MCP initialize handshake failed (exit ${result.exitCode}): ${result.output}"
        }

        println("[IDE-AGENT] MCP Steroid is ready in the container at $guestMcpUrl")
        println("[IDE-AGENT] MCP Steroid is ready in the host at $hostMcpUrl")
    }


    /**
     * Execute Kotlin code via steroid_execute_code tool.
     *
     * This makes a direct HTTP call to the MCP server, bypassing AI agents.
     * Useful for integration tests that need reliable, deterministic behavior.
     *
     * @param code Kotlin code to execute (suspend function body)
     * @param taskId Task identifier (default: "integration-test")
     * @param reason Human-readable reason for execution
     * @param timeout Timeout in seconds (default: 600)
     * @param projectName Project name (default: "test-project")
     * @return MCP tool result as JSON string
     */
    fun mcpExecuteCode(
        code: String,
        taskId: String = "integration-test",
        reason: String = "Integration test execution",
        timeout: Int = 600,
        projectName: String = "test-project",
        dialogKiller: Boolean? = false,
    ): ProcessResult {
        // First, initialize MCP session
        val sessionId = mcpInitialize()

        // Build the tool call request using kotlinx.serialization
        val toolCallRequest = buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", 2)
            putJsonObject("params") {
                put("name", "steroid_execute_code")
                putJsonObject("arguments") {
                    put("project_name", projectName)
                    put("code", code)
                    put("task_id", taskId)
                    put("reason", reason)
                    put("timeout", timeout)
                    if (dialogKiller != null) {
                        put("dialog_killer", dialogKiller)
                    }
                }
            }
            put("method", "tools/call")
        }.toString()

        // Execute the tool call (curl timeout must exceed the server-side execution timeout)
        val run = executeMcpRequest(sessionId, toolCallRequest, timeoutSeconds = timeout.toLong() + 30)
        val data = json.parseToJsonElement(run)

        val messages = buildString {
            data.jsonObject["result"]?.jsonObject["content"]?.jsonArray?.forEach {
                it.jsonObject["text"]?.jsonPrimitive?.contentOrNull?.let { text ->
                    println("[MCP LOG]: $text ")
                    appendLine(text)
                }
            }
        }

        val isError = data.jsonObject["result"]?.jsonObject["isError"]?.jsonPrimitive?.booleanOrNull ?: true

        return ProcessResultValue(
            exitCode = if (isError) 1 else 0,
            output = messages,
            stderr = "",
        )
    }

    /**
     * Initialize MCP session and return session ID.
     */
    private fun mcpInitialize(): String {
        val initRequest = buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", 1)
            put("method", "initialize")
            putJsonObject("params") {
                put("protocolVersion", "2025-11-25")
                putJsonObject("capabilities") { }
                putJsonObject("clientInfo") {
                    put("name", "integration-test")
                    put("version", "1.0")
                }
            }
        }.toString()

        executeMcpRequest(null, initRequest)

        // Return a session ID (the server will manage the actual session)
        return "test-session-${System.currentTimeMillis()}"
    }

    /**
     * Execute an MCP request via curl in the container.
     */
    private fun executeMcpRequest(
        sessionId: String?,
        requestBody: String,
        timeoutSeconds: Long = 30,
    ): String {
        // Create curl command
        val curlCommand = buildList {
            add("curl")
            add("-s")  // Silent
            add("-X")
            add("POST")
            add(guestMcpUrl)
            add("-H")
            add("Content-Type: application/json")
            add("-H")
            add("Accept: application/json")

            // Add session cookie if present
            if (sessionId != null) {
                add("-H")
                add("Cookie: mcp_session=$sessionId")
            }

            add("-d")
            add(requestBody)
        }

        val result = driver.runInContainer(
            curlCommand,
            timeoutSeconds = timeoutSeconds,
        )

        if (result.exitCode != 0) {
            error("MCP request failed (exit ${result.exitCode}): ${result.output}")
        }

        val j = result.output.trim()
        return json.encodeToString(json.parseToJsonElement(j))
    }

}
