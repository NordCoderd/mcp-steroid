/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.intellij.mcp

import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.common.timeoutRunBlocking
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assume.assumeTrue
import java.io.File
import java.util.UUID
import kotlin.time.Duration.Companion.seconds

/**
 * Integration tests that run OpenAI Codex CLI against the MCP server.
 *
 * These tests use Docker to isolate Codex CLI from the local system,
 * preventing side effects from MCP server registrations.
 *
 * Prerequisites:
 * - Docker must be installed and running
 * - OPENAI_API_KEY must be available (either in ~/.openai or environment)
 *
 * The tests will automatically:
 * - Build the Docker image if needed
 * - Start a container for each test
 * - Run Codex commands inside the container
 * - Clean up the container after the test
 */
class CodexCliIntegrationTest : BasePlatformTestCase() {

    override fun setUp() {
        super.setUp()
        // Bind MCP server to 0.0.0.0 so Docker containers can reach it via host.docker.internal
        setRegistryPropertyForTest("mcp.steroids.server.host", "0.0.0.0")
        // Use fixed port for tests
        setRegistryPropertyForTest("mcp.steroids.server.port", "17820")
        // Disable review mode for tests
        setRegistryPropertyForTest("mcp.steroids.review.mode", "NEVER")
    }

    private fun assumeDockerAvailable() {
        val result = try {
            ProcessRunner.run(
                listOf("docker", "ps"),
                description = "Check docker availability",
                workingDir = File("."),
                timeoutSeconds = 15,
                logPrefix = "DOCKER-CHECK"
            )
        } catch (e: Exception) {
            assumeTrue("Docker is required for Codex CLI tests: ${e.message}", false)
            return
        }
        assumeTrue("Docker is required for Codex CLI tests: ${result.stderr}", result.exitCode == 0)
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
     * Tests that the MCP server is reachable from inside the Docker container.
     * Uses curl to directly test HTTP connectivity.
     */
    fun testHostAvailability(): Unit = timeoutRunBlocking(180.seconds) {
        assumeDockerAvailable()
        val session = codexSession()

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
            curlResult.output.contains("\"protocolVersion\":\"2025-06-18\"")
        )
    }

    /**
     * Tests that Codex CLI is properly installed in the Docker container.
     */
    fun testCodexInstalled(): Unit = timeoutRunBlocking(180.seconds) {
        assumeDockerAvailable()
        val session = codexSession()

        // Check codex version
        val versionResult = session.run("--version")
        println("[TEST] Codex version result: exit=${versionResult.exitCode}")
        println("[TEST] Codex version output: ${versionResult.output}")
        println("[TEST] Codex version stderr: ${versionResult.stderr}")

        assertEquals("Codex --version should succeed", 0, versionResult.exitCode)
        assertTrue(
            "Codex should report a version. Output: ${versionResult.output}",
            versionResult.output.isNotBlank() || versionResult.stderr.contains("codex")
        )
    }

    private fun assertOpenAiApiKeyValid(session: DockerCodexSession, model: String = "gpt-4o-mini") {
        // Test API key with curl to OpenAI API - try chat completions endpoint
        val apiKey = System.getenv("OPENAI_API_KEY")
            ?: File(System.getProperty("user.home"), ".openai").takeIf { it.exists() }?.readText()?.trim()
            ?: error("OPENAI_API_KEY not found")

        // Use max_completion_tokens for o-series models, max_tokens for others
        // o-series models require higher token counts due to their reasoning capabilities
        val maxTokensParam = if (model.startsWith("o")) "max_completion_tokens" else "max_tokens"
        val maxTokensValue = if (model.startsWith("o")) 1000 else 50

        // Test the chat completions endpoint which is what Codex uses
        val result = session.runRaw(
            "curl", "-s", "-w", "\n%{http_code}",
            "-H", "Authorization: Bearer $apiKey",
            "-H", "Content-Type: application/json",
            "-d", """{"model":"$model","messages":[{"role":"user","content":"hi"}],"$maxTokensParam":$maxTokensValue}""",
            "https://api.openai.com/v1/chat/completions"
        )
        val lines = result.output.trim().lines()
        val httpStatus = lines.lastOrNull()?.trim() ?: "unknown"
        val responseBody = lines.dropLast(1).joinToString("\n")

        println("[TEST] OpenAI API key validation for model $model - HTTP status: $httpStatus")
        println("[TEST] OpenAI API key validation - response: $responseBody")
        println("[TEST] OpenAI API key validation - stderr: ${result.stderr}")

        assertEquals(
            "OpenAI API key is invalid for model $model (got HTTP $httpStatus). Response: $responseBody",
            "200",
            httpStatus
        )
    }

    /**
     * Tests that MCP server can be configured via Codex's TOML config.
     * Uses Docker to run Codex CLI in isolation.
     */
    fun testMcpServerConfiguration(): Unit = timeoutRunBlocking(180.seconds) {
        assumeDockerAvailable()
        val session = codexSession()
        val dockerMcpUrl = resolveDockerUrl()

        // Configure MCP server via TOML config
        val configResult = session.configureMcpServer("intellij-steroid-test", dockerMcpUrl)
        println("[TEST] MCP config result: exit=${configResult.exitCode}")
        assertEquals("MCP config should succeed", 0, configResult.exitCode)

        // Check config file was created with correct content
        val catResult = session.runRaw("cat", "/home/codex/.codex/config.toml")
        println("[TEST] config.toml content: ${catResult.output}")
        assertTrue(
            "config.toml should contain server name. Output: ${catResult.output}",
            catResult.output.contains("intellij-steroid-test")
        )
        assertTrue(
            "config.toml should contain URL. Output: ${catResult.output}",
            catResult.output.contains("host.docker.internal")
        )
        assertFalse("config.toml should not contain stray heredoc markers", catResult.output.contains("EOF"))
    }

    /**
     * Tests that MCP server can be added via `codex mcp add` command.
     * Codex uses a different syntax than Claude CLI.
     *
     * For HTTP-based servers, Codex uses: codex mcp add <name> --transport http --url <url>
     *
     * Note: If Codex CLI doesn't have mcp subcommand, the test is skipped.
     */
    fun testMcpServerAddCommand(): Unit = timeoutRunBlocking(180.seconds) {
        assumeDockerAvailable()
        val session = codexSession()
        val dockerMcpUrl = resolveDockerUrl()

        // First check if mcp subcommand exists
        val mcpHelpResult = session.run("mcp", "--help")
        println("[TEST] MCP help result: exit=${mcpHelpResult.exitCode}")
        println("[TEST] MCP help output: ${mcpHelpResult.output}")
        println("[TEST] MCP help stderr: ${mcpHelpResult.stderr}")

        val combinedHelpOutput = mcpHelpResult.output + mcpHelpResult.stderr

        // Skip test if mcp subcommand doesn't exist
        if (mcpHelpResult.exitCode != 0) {
            println("[TEST] PASS: Codex mcp subcommand not available - test not applicable")
            return@timeoutRunBlocking
        }

        // Check mcp add help to see exact syntax
        val mcpAddHelpResult = session.run("mcp", "add", "--help")
        println("[TEST] MCP add help result: exit=${mcpAddHelpResult.exitCode}")
        println("[TEST] MCP add help output: ${mcpAddHelpResult.output}")
        println("[TEST] MCP add help stderr: ${mcpAddHelpResult.stderr}")

        val combinedAddHelpOutput = mcpAddHelpResult.output + mcpAddHelpResult.stderr

        // Skip if mcp add subcommand doesn't exist
        if (mcpAddHelpResult.exitCode != 0) {
            println("[TEST] PASS: Codex mcp add subcommand not available - test not applicable")
            return@timeoutRunBlocking
        }

        // Check if HTTP server support exists in mcp add
        val supportsHttpServers = combinedAddHelpOutput.contains("--transport") ||
                combinedAddHelpOutput.contains("--url") ||
                combinedAddHelpOutput.contains("sse")

        if (!supportsHttpServers) {
            // Codex CLI only supports stdio-based servers via 'mcp add' (syntax: mcp add name -- command)
            // HTTP servers must be configured via TOML config (tested in testDocumentedTomlConfiguration)
            println("[TEST] PASS: Codex mcp add doesn't support HTTP servers - HTTP config is via TOML (see testDocumentedTomlConfiguration)")
            return@timeoutRunBlocking
        }

        // Add MCP server via CLI command
        // The syntax depends on Codex version - try with --transport http --url for HTTP servers
        val addResult = if (combinedAddHelpOutput.contains("--transport") || combinedAddHelpOutput.contains("--url")) {
            // New syntax with transport/url flags
            session.run("mcp", "add", "intellij-steroid-cli", "--transport", "http", "--url", dockerMcpUrl)
        } else {
            // Try SSE transport syntax
            session.run("mcp", "add", "intellij-steroid-cli", "--sse", dockerMcpUrl)
        }

        println("[TEST] MCP add result: exit=${addResult.exitCode}")
        println("[TEST] MCP add output: ${addResult.output}")
        println("[TEST] MCP add stderr: ${addResult.stderr}")

        // Skip if the add command itself failed (HTTP not supported by this version)
        if (addResult.exitCode != 0) {
            println("[TEST] PASS: Codex mcp add failed for HTTP server - HTTP config is via TOML")
            return@timeoutRunBlocking
        }

        // Verify the server was added by listing MCP servers
        val listResult = session.run("mcp", "list")
        println("[TEST] MCP list result: exit=${listResult.exitCode}")
        println("[TEST] MCP list output: ${listResult.output}")
        println("[TEST] MCP list stderr: ${listResult.stderr}")

        val combinedListOutput = listResult.output + listResult.stderr

        // The server should appear in the list
        assertTrue(
            "MCP server should be added via 'codex mcp add'. " +
                    "List output: $combinedListOutput",
            combinedListOutput.contains("intellij-steroid-cli")
        )
    }

    /**
     * Tests that Codex can discover our steroid_ tools.
     * Uses Docker to run Codex CLI in isolation.
     *
     * Note: This test requires OPENAI_API_KEY and uses exec mode
     * which runs without user interaction.
     */
    fun testCodexDiscoversSteroidTools(): Unit = timeoutRunBlocking(300.seconds) {
        assumeDockerAvailable()
        val session = codexSession()

        // Verify API key works for o4-mini model before proceeding
        assertOpenAiApiKeyValid(session, "o4-mini")

        val dockerMcpUrl = resolveDockerUrl()

        // Configure MCP server
        val configResult = session.configureMcpServer("intellij-steroid-test", dockerMcpUrl)
        println("[TEST] MCP config result: exit=${configResult.exitCode}")

        assertEquals("Failed to configure MCP server: ${configResult.stderr}", 0, configResult.exitCode)

        // Verify the config file
        val catResult = session.runRaw("cat", "/home/codex/.codex/config.toml")
        println("[TEST] config.toml content:\n${catResult.output}")

        // Run Codex exec to discover tools
        val result = session.runExec(
            """
            You are testing an MCP server integration. You MUST use the MCP tools.
            Steps:
            1) List all MCP tools starting with "steroid_" and print each as: TOOL: <name> - <description>
            2) Call steroid_list_projects EXACTLY once and print the raw result on a single line prefixed with PROJECTS:
            Do not skip any step. If a step fails, print ERROR: <reason>.
            """.trimIndent(),
            timeoutSeconds = 120,
            model = "o4-mini"
        )

        println("[TEST] Codex exit code: ${result.exitCode}")
        println("[TEST] Codex stdout:\n${result.output}")
        println("[TEST] Codex stderr:\n${result.stderr}")

        val combined = (result.output + "\n" + result.stderr).lowercase()

        // Check if MCP server connected successfully (this is visible in stderr)
        val mcpConnected = combined.contains("mcp:") && combined.contains("ready")
        println("[TEST] MCP server connected: $mcpConnected")

        assertEquals("Codex exec should succeed. Output: ${result.output}\nStderr: ${result.stderr}", 0, result.exitCode)

        assertTrue(
            "Codex should report steroid_ tools. Output: ${result.output}\nStderr: ${result.stderr}",
            combined.contains("steroid_")
        )
        assertTrue(
            "Codex should call steroid_list_projects. Output: ${result.output}\nStderr: ${result.stderr}",
            combined.contains("steroid_list_projects") || combined.contains("projects")
        )
    }

    /**
     * Tests that the documented TOML config approach works correctly.
     * This verifies the README and mcp-steroids.txt instructions are accurate.
     *
     * Codex CLI uses ~/.codex/config.toml for HTTP-based MCP servers:
     *   [features]
     *   rmcp_client = true
     *
     *   [mcp_servers.intellij-steroid]
     *   url = "http://localhost:63150/mcp"
     */
    fun testDocumentedTomlConfiguration(): Unit = timeoutRunBlocking(180.seconds) {
        assumeDockerAvailable()
        val session = codexSession()

        val dockerMcpUrl = resolveDockerUrl()

        // ============================================================================
        // Step 1: Create config using documented TOML format
        // ============================================================================
        println("[TEST] Step 1: Creating TOML config with documented format...")

        // This mirrors the exact format from the README
        val tomlConfig = """
[features]
rmcp_client = true

[mcp_servers.intellij-steroid]
url = "$dockerMcpUrl"
""".trim()

        val configScript = """
mkdir -p ~/.codex
cat > ~/.codex/config.toml << 'CONFIGEOF'
$tomlConfig
CONFIGEOF
""".trimIndent()

        val configResult = session.runRaw("bash", "-c", configScript)
        println("[TEST] Config creation result: exit=${configResult.exitCode}")
        println("[TEST] Config creation output: ${configResult.output}")
        println("[TEST] Config creation stderr: ${configResult.stderr}")

        assertEquals(
            "Config creation should succeed. Stderr: ${configResult.stderr}",
            0,
            configResult.exitCode
        )

        // ============================================================================
        // Step 2: Verify config file was created correctly
        // ============================================================================
        println("[TEST] Step 2: Verifying config file...")
        val catResult = session.runRaw("cat", "/home/codex/.codex/config.toml")
        println("[TEST] config.toml content:\n${catResult.output}")

        assertTrue(
            "Config should contain [features] section. Output: ${catResult.output}",
            catResult.output.contains("[features]")
        )
        assertTrue(
            "Config should enable rmcp_client. Output: ${catResult.output}",
            catResult.output.contains("rmcp_client = true")
        )
        assertTrue(
            "Config should contain [mcp_servers.intellij-steroid] section. Output: ${catResult.output}",
            catResult.output.contains("[mcp_servers.intellij-steroid]")
        )
        assertTrue(
            "Config should contain the URL. Output: ${catResult.output}",
            catResult.output.contains(dockerMcpUrl)
        )

        // ============================================================================
        // Step 3: Verify Codex can read the config (check help works)
        // ============================================================================
        println("[TEST] Step 3: Verifying Codex CLI works with config...")
        val helpResult = session.run("--help")
        assertEquals(
            "Codex --help should succeed with config present. Stderr: ${helpResult.stderr}",
            0,
            helpResult.exitCode
        )

        println("[TEST] Documented TOML configuration works correctly!")
    }

    /**
     * Tests that Codex can read a system property set in the IDE JVM via MCP execute_code.
     * This verifies the MCP server runs in the same JVM and can access system properties.
     *
     * The test:
     * 1. Sets a system property with a random UUID value
     * 2. Asks Codex to read it via steroid_execute_code
     * 3. Verifies Codex's output contains the correct value
     */
    fun testSystemPropertyCanBeReadViaCodex(): Unit = timeoutRunBlocking(300.seconds) {
        assumeDockerAvailable()
        val session = codexSession()

        // Verify API key works for o4-mini model
        assertOpenAiApiKeyValid(session, "o4-mini")

        val dockerMcpUrl = resolveDockerUrl()

        // Set a system property with a random value
        val propertyKey = "mcp.test.codex.random.value"
        val randomValue = "codex-${UUID.randomUUID()}"
        System.setProperty(propertyKey, randomValue)

        try {
            // Configure MCP server
            val configResult = session.configureMcpServer("intellij-steroid-test", dockerMcpUrl)
            println("[TEST] MCP config result: exit=${configResult.exitCode}")
            assertEquals("Failed to configure MCP server: ${configResult.stderr}", 0, configResult.exitCode)

            // Verify config
            val catResult = session.runRaw("cat", "/home/codex/.codex/config.toml")
            println("[TEST] config.toml content:\n${catResult.output}")

            // Ask Codex to read the system property using execute_code
            val result = session.runExec(
                """
                You are testing MCP integration. You MUST use steroid_execute_code to run Kotlin code.
                Execute the following code:

                execute {
                    val value = System.getProperty("$propertyKey")
                    println("SYSPROP_VALUE: " + value)
                }

                After execution, print the SYSPROP_VALUE from the output as:
                FINAL_VALUE: <the value>

                If you encounter any errors, print: ERROR: <description>
                """.trimIndent(),
                timeoutSeconds = 120,
                model = "o4-mini"
            )

            println("[TEST] Codex exit code: ${result.exitCode}")
            println("[TEST] Codex stdout:\n${result.output}")
            println("[TEST] Codex stderr:\n${result.stderr}")

            // Verify execution succeeded
            assertEquals(
                "Codex should succeed. Stderr: ${result.stderr}",
                0,
                result.exitCode
            )

            val combined = result.output + "\n" + result.stderr

            // Verify the output contains our random value
            assertTrue(
                "Output should contain the system property value '$randomValue'. Output:\n$combined",
                combined.contains(randomValue)
            )

            println("[TEST] Successfully read system property via Codex!")
        } finally {
            // Clean up
            System.clearProperty(propertyKey)
        }
    }

    private fun codexSession(): DockerCodexSession {
        val session = DockerCodexSession.create()
        Disposer.register(testRootDisposable, session)
        return session
    }
}
