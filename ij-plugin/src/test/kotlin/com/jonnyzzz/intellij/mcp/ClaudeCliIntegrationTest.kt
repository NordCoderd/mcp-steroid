/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.intellij.mcp

import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.common.timeoutRunBlocking
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import junit.framework.TestCase.assertEquals
import junit.framework.TestCase.assertTrue
import org.junit.Assume.assumeTrue
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

    override fun setUp() {
        super.setUp()
        // Bind MCP server to 0.0.0.0 so Docker containers can reach it via host.docker.internal
        setRegistryPropertyForTest("mcp.steroids.server.host", "0.0.0.0")
        // Use dynamic port to avoid conflicts
        setRegistryPropertyForTest("mcp.steroids.server.port", "0")
        // Disable review mode for tests
        setRegistryPropertyForTest("mcp.steroids.review.mode", "NEVER")
    }

    private fun assumeDockerAvailable() {
        val result = ProcessRunner.run(
            listOf("docker", "ps"),
            description = "Check docker availability",
            workingDir = java.io.File("."),
            timeoutSeconds = 10,
            logPrefix = "DOCKER-CHECK"
        )
        assumeTrue("Docker is not available (exit code ${result.exitCode})", result.exitCode == 0)
    }

    private fun resolveDockerUrl(): String {
        // Docker on macOS runs in a VM, so localhost inside container != host's localhost
        // Use host.docker.internal to access the host from inside Docker
        val mcpUrl = McpTestUtil.getSseUrlIfRunning()
        val dockerUrl = mcpUrl.replace("localhost", "host.docker.internal")
            .replace("127.0.0.1", "host.docker.internal")
        println("[TEST] MCP URL: $mcpUrl -> Docker URL: $dockerUrl")
        return dockerUrl
    }

    /**
     * Tests that the MCP server endpoint is reachable from Docker container via curl.
     */
    fun testHostAvailability(): Unit = timeoutRunBlocking(180.seconds) {
        assumeDockerAvailable()
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

        assertEquals("Curl should succeed. stderr: ${curlResult.stderr}", 0, curlResult.exitCode)
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
     * Tests that Claude CLI is properly installed in the Docker container.
     */
    fun testClaudeInstalled(): Unit = timeoutRunBlocking(180.seconds) {
        assumeDockerAvailable()
        val session = claudeSession()

        val versionResult = session.run("--version")
        println("[TEST] Claude version result: exit=${versionResult.exitCode}")
        println("[TEST] Claude version output: ${versionResult.output}")
        println("[TEST] Claude version stderr: ${versionResult.stderr}")

        assertEquals("Claude --version should succeed", 0, versionResult.exitCode)
    }

    /**
     * Tests that MCP server can be registered and listed via Claude CLI.
     * Uses Docker to run Claude CLI in isolation.
     */
    fun testMcpServerRegistration(): Unit = timeoutRunBlocking(180.seconds) {
        assumeDockerAvailable()
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

        // Check .mcp.json file was created with correct content
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

        // Verify server is registered via `mcp get`
        val getResult = session.run("mcp", "get", "intellij-steroid-test")
        println("[TEST] MCP get result: exit=${getResult.exitCode}")
        println("[TEST] MCP get output: ${getResult.output}")
        println("[TEST] MCP get stderr: ${getResult.stderr}")

        assertEquals("MCP get should succeed", 0, getResult.exitCode)
        assertTrue(
            "MCP get should find server 'intellij-steroid-test'. Output: ${getResult.output}",
            getResult.output.contains("intellij-steroid-test")
        )
        assertTrue(
            "MCP get should show correct URL. Output: ${getResult.output}",
            getResult.output.contains("host.docker.internal")
        )
    }

    private fun assertAnthropicApiKeyValid(session: DockerClaudeSession) {
        // Test API key with curl to Anthropic API
        val apiKey = System.getenv("ANTHROPIC_API_KEY")
            ?: java.io.File(System.getProperty("user.home"), ".anthropic").takeIf { it.exists() }?.readText()?.trim()
            ?: error("ANTHROPIC_API_KEY not found")

        // Test the messages endpoint
        val result = session.runRaw(
            "curl", "-s", "-w", "\n%{http_code}",
            "-H", "x-api-key: $apiKey",
            "-H", "anthropic-version: 2023-06-01",
            "-H", "Content-Type: application/json",
            "-d", """{"model":"claude-3-haiku-20240307","max_tokens":10,"messages":[{"role":"user","content":"hi"}]}""",
            "https://api.anthropic.com/v1/messages"
        )
        val lines = result.output.trim().lines()
        val httpStatus = lines.lastOrNull()?.trim() ?: "unknown"
        val responseBody = lines.dropLast(1).joinToString("\n")

        println("[TEST] Anthropic API key validation - HTTP status: $httpStatus")
        println("[TEST] Anthropic API key validation - response: $responseBody")
        println("[TEST] Anthropic API key validation - stderr: ${result.stderr}")

        assertEquals(
            "Anthropic API key is invalid (got HTTP $httpStatus). Response: $responseBody",
            "200",
            httpStatus
        )
    }

    /**
     * Tests MCP server connection verification via claude mcp get.
     *
     * Note: Claude CLI print mode (-p) does NOT support MCP tools.
     * This is a known limitation - see https://github.com/anthropics/claude-code/issues/610
     *
     * This test verifies:
     * - MCP server can be registered
     * - MCP server URL is stored correctly
     * - Server connectivity status is reported
     */
    fun testMcpServerConnectivity(): Unit = timeoutRunBlocking(300.seconds) {
        assumeDockerAvailable()
        val session = claudeSession()

        // Verify API key works
        assertAnthropicApiKeyValid(session)

        val dockerMcpUrl = resolveDockerUrl()

        // Register the MCP server
        val addResult = session.run(
            "mcp", "add",
            "--transport", "http",
            "--scope", "project",
            "intellij-steroid-test",
            dockerMcpUrl
        )
        println("[TEST] MCP add result: exit=${addResult.exitCode}")
        assertEquals("Failed to add MCP server: ${addResult.stderr}", 0, addResult.exitCode)

        // Verify the config file
        val catResult = session.runRaw("cat", ".mcp.json")
        println("[TEST] .mcp.json content:\n${catResult.output}")
        assertTrue(
            ".mcp.json should contain type http. Output: ${catResult.output}",
            catResult.output.contains("\"type\": \"http\"")
        )

        // Check server status via mcp get
        val getResult = session.run("mcp", "get", "intellij-steroid-test")
        println("[TEST] MCP get result: exit=${getResult.exitCode}")
        println("[TEST] MCP get output:\n${getResult.output}")
        println("[TEST] MCP get stderr:\n${getResult.stderr}")

        assertEquals("MCP get should succeed", 0, getResult.exitCode)

        // Verify server info is correct
        val output = getResult.output
        assertTrue(
            "MCP get should show server name. Output: $output",
            output.contains("intellij-steroid-test")
        )
        assertTrue(
            "MCP get should show http transport type. Output: $output",
            output.contains("http")
        )
        assertTrue(
            "MCP get should show URL. Output: $output",
            output.contains("host.docker.internal")
        )

        // Note: We don't test actual MCP tool calls because Claude CLI print mode (-p)
        // does not support MCP tools. See https://github.com/anthropics/claude-code/issues/610
        // For interactive mode testing, manual testing is required.
    }

    private fun claudeSession(): DockerClaudeSession {
        val session = DockerClaudeSession.create()
        Disposer.register(testRootDisposable, session)
        return session
    }
}
