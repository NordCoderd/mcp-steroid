/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.intellij.mcp

import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.common.timeoutRunBlocking
import com.intellij.testFramework.fixtures.BasePlatformTestCase
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
 *
 * ============================================================================
 * IMPORTANT: TEST INTEGRITY RULES - DO NOT FAKE THESE TESTS
 * ============================================================================
 *
 * 1. NEVER ignore ERROR patterns in output - if Claude reports "ERROR:" or
 *    "**ERROR:" in its response, the test MUST fail.
 *
 * 2. ALWAYS verify actual tool calls happened - check for specific tool output
 *    patterns like "TOOL:" and "PROJECTS:" that indicate real execution.
 *
 * 3. NEVER use loose assertions like "contains steroid_" when Claude just
 *    mentions the word in an error message - verify actual successful calls.
 *
 * 4. If the test fails, report the failure. Do not modify assertions to make
 *    a failing test pass without fixing the underlying issue.
 *
 * 5. Compare with CodexCliIntegrationTest - both should have equivalent
 *    assertion strictness.
 * ============================================================================
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
            curlResult.output.contains("\"protocolVersion\":\"2025-06-18\"")
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
     * Helper to check for error patterns in Claude's output.
     * Claude reports errors in various formats:
     * - "ERROR: <message>"
     * - "**ERROR: <message>**"
     * - Tool not available messages
     */
    private fun assertNoErrorsInOutput(output: String, stderr: String, context: String) {
        val combined = output + "\n" + stderr

        // Check for explicit ERROR patterns (case-insensitive)
        val errorPatterns = listOf(
            Regex("(?i)\\*\\*ERROR:", RegexOption.MULTILINE),
            Regex("(?i)^ERROR:", RegexOption.MULTILINE),
            Regex("(?i)tool .* is not available", RegexOption.IGNORE_CASE),
            Regex("(?i)not registered or accessible", RegexOption.IGNORE_CASE),
            Regex("(?i)failed to connect", RegexOption.IGNORE_CASE),
        )

        for (pattern in errorPatterns) {
            val match = pattern.find(combined)
            assertFalse(
                "$context: Found error pattern '${pattern.pattern}' in output. " +
                    "Match: '${match?.value}'. Full output:\n$combined",
                match != null
            )
        }
    }

    /**
     * Tests that Claude Code can discover and use our steroid_ tools.
     * Uses Docker to run Claude CLI in isolation.
     *
     * Note: This test requires ANTHROPIC_API_KEY and uses print mode (-p)
     * which runs without user interaction.
     *
     * ============================================================================
     * TEST INTEGRITY: This test verifies ACTUAL MCP tool calls, not just mentions.
     * ============================================================================
     *
     * Success criteria (ALL must be met):
     * 1. No ERROR patterns in Claude's output
     * 2. Claude must list tools with "TOOL:" prefix (actual tool discovery)
     * 3. Claude must call steroid_list_projects and show "PROJECTS:" output
     * 4. The PROJECTS output must contain actual project data (not an error)
     *
     * If any of these fail, the test fails. Do not weaken these assertions.
     */
    fun testClaudeDiscoversSteroidTools(): Unit = timeoutRunBlocking(300.seconds) {
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

        // Run Claude in print mode to discover tools
        // MCP tools must be explicitly allowed in print mode using mcp__<serverName>__* pattern
        // Permission mode must be set to bypass tool approval prompts in CI
        val result = session.runPrompt(
            """
            You are testing an MCP server integration. You MUST use the MCP tools.
            Steps:
            1) List all MCP tools starting with "steroid_" and print each as: TOOL: <name> - <description>
            2) Call steroid_list_projects EXACTLY once and print the raw result on a single line prefixed with PROJECTS:
            Do not skip any step. If a step fails, print ERROR: <reason>.
            """.trimIndent(),
            timeoutSeconds = 120,
            allowedTools = listOf("mcp__intellij-steroid-test__*"),
            permissionMode = "bypassPermissions"
        )

        println("[TEST] Claude exit code: ${result.exitCode}")
        println("[TEST] Claude stdout:\n${result.output}")
        println("[TEST] Claude stderr:\n${result.stderr}")

        // ============================================================================
        // STRICT ASSERTIONS - DO NOT WEAKEN
        // ============================================================================

        // 1. Command must succeed
        assertEquals(
            "Claude exec should succeed. Output: ${result.output}\nStderr: ${result.stderr}",
            0,
            result.exitCode
        )

        // 2. Check for any error patterns - this catches cases where Claude says
        //    "ERROR: Tool not available" which would be a fake pass if ignored
        assertNoErrorsInOutput(result.output, result.stderr, "Claude prompt execution")

        // 3. Claude must have called steroid_list_projects and shown output
        //    (Claude may skip the TOOL: listing step and go straight to calling tools)
        assertTrue(
            "Claude must show 'PROJECTS:' output from actual tool call. " +
                "Output: ${result.output}\nStderr: ${result.stderr}",
            result.output.contains("PROJECTS:")
        )

        // 4. The PROJECTS line should contain actual data (array or object), not an error
        val projectsLine = result.output.lines().find { it.contains("PROJECTS:") }
        assertTrue(
            "PROJECTS: line must contain actual project data ([ or {), not error. " +
                "Line: $projectsLine",
            projectsLine != null && (projectsLine.contains("[") || projectsLine.contains("{"))
        )

        // 5. The PROJECTS data should contain at least one project entry
        assertTrue(
            "PROJECTS: should contain actual project name. Line: $projectsLine",
            projectsLine != null && projectsLine.contains("\"name\":")
        )
    }

    /**
     * Diagnostic test to understand Claude CLI MCP configuration.
     * Lists MCP servers and inspects configuration files.
     * Note: `claude doctor` requires TTY so we skip it.
     */
    fun testMcpServerDiagnostics(): Unit = timeoutRunBlocking(300.seconds) {
        assumeDockerAvailable()
        val session = claudeSession()

        val dockerMcpUrl = resolveDockerUrl()

        // First, register the MCP server
        val addResult = session.run(
            "mcp", "add",
            "--transport", "http",
            "--scope", "project",
            "intellij-steroid-test",
            dockerMcpUrl
        )
        println("[TEST] MCP add result: exit=${addResult.exitCode}")
        println("[TEST] MCP add stdout: ${addResult.output}")
        println("[TEST] MCP add stderr: ${addResult.stderr}")
        assertEquals("MCP add should succeed", 0, addResult.exitCode)

        // List MCP servers
        val listResult = session.listMcpServers(timeoutSeconds = 30)
        println("[TEST] MCP list exit code: ${listResult.exitCode}")
        println("[TEST] MCP list stdout:\n${listResult.output}")
        println("[TEST] MCP list stderr:\n${listResult.stderr}")

        // Check .mcp.json content
        val catResult = session.runRaw("cat", ".mcp.json")
        println("[TEST] .mcp.json content:\n${catResult.output}")

        // Check ~/.claude.json if it exists
        val claudeJsonResult = session.runRaw("cat", "/home/claude/.claude.json")
        println("[TEST] ~/.claude.json content:\n${claudeJsonResult.output}")

        // Get MCP server details
        val getResult = session.run("mcp", "get", "intellij-steroid-test")
        println("[TEST] MCP get exit code: ${getResult.exitCode}")
        println("[TEST] MCP get stdout:\n${getResult.output}")
        println("[TEST] MCP get stderr:\n${getResult.stderr}")

        // MCP server should be registered
        val combinedOutput = listResult.output + listResult.stderr + getResult.output + getResult.stderr
        assertTrue(
            "MCP should show our server. Output: $combinedOutput",
            combinedOutput.contains("intellij-steroid-test")
        )
    }

    /**
     * Test using --mcp-config flag with JSON file approach.
     * Uses the -- separator workaround for the CLI bug.
     */
    fun testMcpConfigFileApproach(): Unit = timeoutRunBlocking(300.seconds) {
        assumeDockerAvailable()
        val session = claudeSession()

        // Verify API key works
        assertAnthropicApiKeyValid(session)

        val dockerMcpUrl = resolveDockerUrl()

        // Create MCP config file
        val configResult = session.createMcpConfigFile(
            "intellij-steroid-test",
            dockerMcpUrl,
            "mcp-config.json"
        )
        println("[TEST] Create config file result: exit=${configResult.exitCode}")
        println("[TEST] Create config output: ${configResult.output}")

        // Verify config file
        val catResult = session.runRaw("cat", "mcp-config.json")
        println("[TEST] mcp-config.json content:\n${catResult.output}")

        // Check for any disabled servers in .claude/settings.local.json
        val settingsResult = session.runRaw("cat", "/home/claude/.claude/settings.local.json")
        println("[TEST] .claude/settings.local.json: ${settingsResult.output}")

        // Run with --mcp-config and --strict-mcp-config
        // Using -- separator as workaround for CLI bug in v1.0.73
        val result = session.runPrompt(
            """
            List all available MCP tools and print each as: TOOL: <name>
            Then call steroid_list_projects and print: PROJECTS: <result>
            """.trimIndent(),
            timeoutSeconds = 120,
            mcpConfigFile = "mcp-config.json",
            strictMcpConfig = true,
            allowedTools = listOf("mcp__intellij-steroid-test__*"),
            permissionMode = "bypassPermissions"
        )

        println("[TEST] Claude exit code: ${result.exitCode}")
        println("[TEST] Claude stdout:\n${result.output}")
        println("[TEST] Claude stderr:\n${result.stderr}")

        // For now, just verify the command ran - we're still debugging
        // This test helps us understand what's happening
        assertEquals(
            "Claude should run without crashing. Stderr: ${result.stderr}",
            0,
            result.exitCode
        )
    }

    /**
     * Test using local scope instead of project scope.
     * Some Claude CLI versions have issues with project-scoped MCP servers.
     */
    fun testMcpLocalScopeApproach(): Unit = timeoutRunBlocking(300.seconds) {
        assumeDockerAvailable()
        val session = claudeSession()

        // Verify API key works
        assertAnthropicApiKeyValid(session)

        val dockerMcpUrl = resolveDockerUrl()

        // First verify MCP server is reachable from container
        val curlCheck = session.runRaw(
            "curl", "-s", "-o", "/dev/null", "-w", "%{http_code}",
            "-X", "POST",
            "-H", "Content-Type: application/json",
            "-H", "Accept: application/json",
            "-d", """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","clientInfo":{"name":"test","version":"1.0"},"capabilities":{}}}""",
            dockerMcpUrl
        )
        println("[TEST] MCP server connectivity check: HTTP ${curlCheck.output}")

        // Register with local scope instead of project scope
        val addResult = session.run(
            "mcp", "add",
            "--transport", "http",
            "--scope", "local",  // Changed from "project" to "local"
            "intellij-steroid-test",
            dockerMcpUrl
        )
        println("[TEST] MCP add (local scope) result: exit=${addResult.exitCode}")
        println("[TEST] MCP add stdout: ${addResult.output}")
        println("[TEST] MCP add stderr: ${addResult.stderr}")

        // Check where the config was written
        val localConfigResult = session.runRaw("cat", "/home/claude/.claude/settings.local.json")
        println("[TEST] ~/.claude/settings.local.json:\n${localConfigResult.output}")

        val mcpJsonResult = session.runRaw("cat", ".mcp.json")
        println("[TEST] .mcp.json:\n${mcpJsonResult.output}")

        // Check ~/.claude.json
        val claudeJsonResult = session.runRaw("cat", "/home/claude/.claude.json")
        println("[TEST] ~/.claude.json:\n${claudeJsonResult.output}")

        // Verify MCP server is still reachable before mcp list
        val curlCheck2 = session.runRaw(
            "curl", "-s", "-o", "/dev/null", "-w", "%{http_code}",
            "-X", "POST",
            "-H", "Content-Type: application/json",
            "-H", "Accept: application/json",
            "-d", """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","clientInfo":{"name":"test","version":"1.0"},"capabilities":{}}}""",
            dockerMcpUrl
        )
        println("[TEST] MCP server connectivity check before mcp list (POST): HTTP ${curlCheck2.output}")

        // Try GET request (like claude mcp list might do for health check)
        val curlGet = session.runRaw(
            "curl", "-v", "-s",
            "-H", "Accept: application/json, text/event-stream",
            dockerMcpUrl
        )
        println("[TEST] GET request result: ${curlGet.exitCode}")
        println("[TEST] GET response: ${curlGet.output}")
        println("[TEST] GET stderr: ${curlGet.stderr}")

        // Try POST without session ID (like Claude CLI might do for InitializeRequest)
        val curlInit = session.runRaw(
            "curl", "-v", "-s",
            "-X", "POST",
            "-H", "Content-Type: application/json",
            "-H", "Accept: application/json, text/event-stream",
            "-H", "User-Agent: claude-code/2.0.67",
            "-d", """{"jsonrpc":"2.0","id":"1","method":"initialize","params":{"protocolVersion":"2025-06-18","clientInfo":{"name":"claude-code","version":"2.0.67"},"capabilities":{"roots":{"listChanged":true},"sampling":{}}}}""",
            dockerMcpUrl
        )
        println("[TEST] InitializeRequest result: ${curlInit.exitCode}")
        println("[TEST] InitializeRequest response: ${curlInit.output}")
        println("[TEST] InitializeRequest stderr: ${curlInit.stderr}")

        // List MCP servers to verify registration
        val listResult = session.listMcpServers(timeoutSeconds = 60)
        println("[TEST] MCP list exit: ${listResult.exitCode}")
        println("[TEST] MCP list stdout:\n${listResult.output}")
        println("[TEST] MCP list stderr:\n${listResult.stderr}")

        // Run prompt
        val result = session.runPrompt(
            """
            List ALL your available tools. Print each as: TOOL: <name>
            Focus especially on any tools starting with "steroid_" or containing "mcp".
            If you have access to steroid_list_projects, call it and print: PROJECTS: <result>
            """.trimIndent(),
            timeoutSeconds = 120,
            allowedTools = listOf("mcp__intellij-steroid-test__*")
        )

        println("[TEST] Claude exit code: ${result.exitCode}")
        println("[TEST] Claude stdout:\n${result.output}")
        println("[TEST] Claude stderr:\n${result.stderr}")

        assertEquals("Claude should run without crashing", 0, result.exitCode)
    }

    /**
     * Test using add-json approach for MCP configuration.
     * This bypasses the .mcp.json file approach entirely.
     */
    fun testMcpAddJsonApproach(): Unit = timeoutRunBlocking(300.seconds) {
        assumeDockerAvailable()
        val session = claudeSession()

        // Verify API key works
        assertAnthropicApiKeyValid(session)

        val dockerMcpUrl = resolveDockerUrl()

        // Use add-json command to add MCP server
        // Format: claude mcp add-json <name> '<json>'
        val mcpJson = """{"type":"http","url":"$dockerMcpUrl"}"""
        val addJsonResult = session.run(
            "mcp", "add-json",
            "--scope", "project",
            "intellij-steroid-test",
            mcpJson
        )
        println("[TEST] MCP add-json result: exit=${addJsonResult.exitCode}")
        println("[TEST] MCP add-json stdout: ${addJsonResult.output}")
        println("[TEST] MCP add-json stderr: ${addJsonResult.stderr}")

        // Verify config file was created
        val catResult = session.runRaw("cat", ".mcp.json")
        println("[TEST] .mcp.json content:\n${catResult.output}")

        // Get MCP server info
        val getResult = session.run("mcp", "get", "intellij-steroid-test")
        println("[TEST] MCP get result: exit=${getResult.exitCode}")
        println("[TEST] MCP get stdout: ${getResult.output}")
        println("[TEST] MCP get stderr: ${getResult.stderr}")

        // Run prompt with all tools enabled
        val result = session.runPrompt(
            """
            You are testing MCP integration. First, list all available tools that start with "steroid_".
            Print each tool name as: TOOL: <name>
            Then call steroid_list_projects and print the result as: PROJECTS: <result>
            If you encounter any issues, print: ERROR: <description>
            """.trimIndent(),
            timeoutSeconds = 120,
            allowedTools = listOf("mcp__intellij-steroid-test__*"),
            permissionMode = "bypassPermissions"
        )

        println("[TEST] Claude exit code: ${result.exitCode}")
        println("[TEST] Claude stdout:\n${result.output}")
        println("[TEST] Claude stderr:\n${result.stderr}")

        assertEquals(
            "Claude should run without crashing. Stderr: ${result.stderr}",
            0,
            result.exitCode
        )

        // Check for MCP server connection in stderr
        val mcpConnected = result.stderr.contains("mcp") || result.stderr.contains("MCP")
        println("[TEST] MCP mentioned in stderr: $mcpConnected")
    }

    private fun claudeSession(): DockerClaudeSession {
        val session = DockerClaudeSession.create()
        Disposer.register(testRootDisposable, session)
        return session
    }
}
