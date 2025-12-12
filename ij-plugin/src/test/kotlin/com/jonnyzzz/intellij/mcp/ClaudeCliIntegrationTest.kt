/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.intellij.mcp

import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.common.timeoutRunBlocking
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.io.File
import kotlin.time.Duration.Companion.seconds

/**
 * Integration tests that run Claude Code CLI against the MCP server.
 *
 * These tests use Docker to isolate Claude CLI from the local system,
 * preventing side effects from MCP server registrations.
 *
 * Prerequisites:
 * - Docker must be installed and running
 * - ANTHROPIC_API_KEY must be available (either in ~/.anthropic or environment)
 *
 * The tests will automatically:
 * - Build the Docker image if needed
 * - Start a container for each test
 * - Run Claude commands inside the container
 * - Clean up the container after the test
 */
class ClaudeCliIntegrationTest : BasePlatformTestCase() {

    fun resolveDockerUrl(): String {
        val mcpUrl = McpTestUtil.getSseUrlIfRunning()

        val dockerMcpUrl = mcpUrl.replace("localhost", "host.docker.internal")
            .replace("127.0.0.1", "host.docker.internal")

        println("[TEST] MCP URL: $mcpUrl (Docker: $dockerMcpUrl)")

        return dockerMcpUrl
    }

    fun testHostAvailability(): Unit = timeoutRunBlocking(180.seconds) {
        val session = claudeSession()

        val curlResult = session.runRaw(
            "curl",
            "-v",
            "-X",
            "POST",
            "-H",
            "Content-Type: application/json",
            "-H",
            "Accept: application/json",
            "-d",
            """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","clientInfo":{"name":"test","version":"1.0"},"capabilities":{}}}""",

            resolveDockerUrl()
        )
        println("[TEST] Curl MCP endpoint result: exit=${curlResult.exitCode}")
        println("[TEST] Curl output: ${curlResult.output}")
        println("[TEST] Curl stderr: ${curlResult.stderr}")
        assertTrue(
            "Curl should succeed (got exit code ${curlResult.exitCode}). stderr: ${curlResult.stderr}",
            curlResult.exitCode == 0
        )
        assertTrue(
            "MCP response should contain jsonrpc. Output: ${curlResult.output}",
            curlResult.output.contains("jsonrpc")
        )
        assertTrue(
            "MCP response should contain protocol version. Output: ${curlResult.output}",
            curlResult.output.contains(" \"protocolVersion\": \"2025-06-18\"")
        )
    }

    /**
     * Tests that MCP server can be registered and listed via Claude CLI.
     * Uses Docker to run Claude CLI in isolation.
     * This test does NOT require ANTHROPIC_API_KEY since it only uses
     * the `mcp` subcommands which don't need API access.
     */
    fun testMcpServerRegistration(): Unit = timeoutRunBlocking(180.seconds) {
        val session = claudeSession()

        val dockerMcpUrl = resolveDockerUrl()

        val addResult = session.run(
            "mcp", "add",
            "--transport", "http",
            "--scope", "project",
            "intellij-steroid-test",
            dockerMcpUrl
        )

        println("[TEST] MCP add result: exit=${addResult.exitCode}")
        assertEquals("MCP add should succeed", 0, addResult.exitCode)

        // Step 3: Check .mcp.json file was created with correct content
        val catResult = session.runRaw("cat", ".mcp.json")
        println("[TEST] .mcp.json content: ${catResult.output}")
        assertTrue(
            ".mcp.json should contain server name. Output: ${catResult.output}",
            catResult.output.contains("intellij-steroid-test")
        )
        assertTrue(
            ".mcp.json should contain URL. Output: ${catResult.output}",
            catResult.output.contains("host.docker.internal")
        )


        // Verify server connection status
        val getResult = session.run("mcp", "get", "intellij-steroid-test")
        println("[TEST] MCP get result: exit=${getResult.exitCode}, output=${getResult.output}")

        assertEquals("MCP get should succeed", 0, getResult.exitCode)
        assertTrue(
            "MCP get should find server 'intellij-steroid-test'. Output: ${getResult.output}",
            getResult.output.contains("intellij-steroid-test")
        )

        // Verify the server is actually connected - fail if "Failed to connect" is in the output
        assertFalse(
            "MCP server should be connected, but got: ${getResult.output}",
            getResult.output.contains("Failed to connect")
        )
        assertTrue(
            "MCP server should show successful connection status. Output: ${getResult.output}",
            getResult.output.contains("Status:") && !getResult.output.contains("✗")
        )
    }

    /**
     * Tests that Claude Code can discover our steroid_ tools.
     * Uses Docker to run Claude CLI in isolation.
     *
     * Note: This test requires ANTHROPIC_API_KEY and may fail if Claude's --print
     * mode doesn't connect to MCP servers (known limitation).
     */
    fun testClaudeCodeDiscoversSteroidTools(): Unit = timeoutRunBlocking(180.seconds) {
        val dockerMcpUrl = resolveDockerUrl()

        val session = claudeSession()
        // Register the MCP server
        val addResult = session.run(
            "mcp", "add",
            "--transport", "http",
            "--scope", "project",
            "intellij-steroid-test",
            dockerMcpUrl
        )
        println("[TEST] MCP add result: exit=${addResult.exitCode}")

        if (addResult.exitCode != 0) {
            println("[TEST] Failed to add MCP server: ${addResult.stderr}")
            fail("Failed to add MCP server")
            return@timeoutRunBlocking
        }

        // Run Claude to discover tools
        val result = session.run(
            "--print",
            """
                You are testing an MCP server integration.

                1. List all available MCP tools that start with "steroid_"
                2. For each tool, print its name and a one-line description
                3. Call steroid_list_projects and print the result

                Be concise. Output format:
                TOOLS_FOUND: [number]
                TOOL: [name] - [description]
                PROJECTS: [result]
                """.trimIndent()
        )

        // Check if Claude discovered tools
        if (result.output.contains("TOOLS_FOUND: 0") || !result.output.contains("steroid_")) {
            fail("[TEST] Claude Code did not discover steroid_ tools.")
            return@timeoutRunBlocking
        }

        assertTrue(
            "Claude should find steroid_ tools. Output: ${result.output}",
            result.output.contains("steroid_") || result.output.contains("TOOLS_FOUND")
        )
    }

    private fun claudeSession(): DockerClaudeSession {
        val session = DockerClaudeSession.create(getApiKey())
        Disposer.register(testRootDisposable, session)
        return session
    }

    // ==================== Helper Methods ====================

    private fun getApiKey(): String {
        System.getenv("ANTHROPIC_API_KEY")?.takeIf { it.isNotBlank() }?.let { return it }
        val anthropicFile = File(System.getenv("HOME"), ".anthropic")
        require(anthropicFile.exists()) { "ANTHROPIC_API_KEY not found in environment or ~/.anthropic" }
        return anthropicFile.readText().trim()
    }
}

