/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.intellij.mcp

import com.intellij.testFramework.common.timeoutRunBlocking
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.testcontainers.containers.GenericContainer
import org.testcontainers.images.builder.ImageFromDockerfile
import org.testcontainers.utility.DockerImageName
import java.io.File
import java.nio.file.Path
import java.time.Duration
import kotlin.time.Duration.Companion.minutes

/**
 * Docker-based integration tests for Claude CLI against the MCP server.
 *
 * These tests run Claude CLI in a Docker container for isolation and safety.
 * The container uses host networking to access the IntelliJ MCP server.
 *
 * Prerequisites:
 * - Docker must be running
 * - ANTHROPIC_API_KEY must be available
 * - Claude CLI Docker image must be pre-built (see src/test/docker/claude-cli/Dockerfile)
 *
 * Note: Uses JUnit 3 style with BasePlatformTestCase.
 */
class ClaudeDockerIntegrationTest : BasePlatformTestCase() {

    companion object {
        // Claude CLI binary path
        private const val CLAUDE_BIN = "/root/.local/bin/claude"

        // Lazy-built image with Claude CLI pre-installed from external Dockerfile
        private val claudeImage: ImageFromDockerfile by lazy {
            val dockerfilePath = Path.of("src/test/docker/claude-cli")
            ImageFromDockerfile("claude-cli-test", false)
                .withDockerfile(dockerfilePath.resolve("Dockerfile"))
                .withFileFromPath(".", dockerfilePath)
        }
    }

    // Use host.docker.internal on macOS/Windows, or 172.17.0.1 on Linux
    private fun getDockerHost(): String {
        return System.getProperty("os.name").lowercase().let { os ->
            when {
                os.contains("mac") || os.contains("windows") -> "host.docker.internal"
                else -> "172.17.0.1" // Default Docker bridge gateway on Linux
            }
        }
    }

    private inline fun withMcpServerInDocker(action: (url: String) -> Unit) {
        McpTestUtil.withMcpServer { port, _ ->
            @Suppress("HttpUrlsUsage")
            val url = "http://${getDockerHost()}:$port/sse"
            action(url)
        }
    }

    private fun createClaudeContainer(): GenericContainer<*> {
        return GenericContainer(claudeImage)
            .withNetworkMode("host")
            .withEnv("ANTHROPIC_API_KEY", getApiKey())
            .withStartupTimeout(Duration.ofMinutes(5))
    }

    /**
     * Tests that the MCP server port is accessible from Docker.
     */
    fun testPortAccessible(): Unit = timeoutRunBlocking(5.minutes) {
        withMcpServerInDocker { url ->
            println("[TEST] MCP Server URL: $url")

            val container = GenericContainer(DockerImageName.parse("curlimages/curl:latest"))
                .withNetworkMode("host")
                .withCommand(
                    "curl", "-s", "-o", "/dev/null", "-w", "%{http_code}",
                    "--connect-timeout", "5",
                    url
                )

            container.start()
            try {
                Thread.sleep(2000)
                val logs = container.logs.trim()
                println("[TEST] Host check response: $logs")
                // 403 means server is accessible but requires proper MCP handshake
                assertEquals("Expected 403 (server accessible)", 403, logs.toInt())
            } finally {
                container.stop()
            }
        }
    }

    /**
     * Tests that Claude CLI in Docker can list MCP servers.
     */
    fun testClaudeInDockerCanListMcpServers(): Unit = timeoutRunBlocking(5.minutes) {
        withMcpServerInDocker { url ->
            println("[TEST] MCP Server URL: $url")

            val container = createClaudeContainer()

            try {
                container.start()
                println("[TEST] Container started: ${container.containerId}")

                // Check if Claude is installed
                val checkClaude = container.execInContainer("bash", "-c", "ls -la /root/.claude/bin/ 2>&1 || echo 'not found'")
                println("[TEST] Claude bin check: ${checkClaude.stdout}")

                val whichClaude = container.execInContainer("bash", "-c", "which claude 2>&1 || echo 'not in PATH'")
                println("[TEST] Which claude: ${whichClaude.stdout}")

                // Add MCP server
                val addServer = container.execInContainer(
                    "bash", "-c",
                    "$CLAUDE_BIN mcp add --transport sse intellij-steroid '$url'"
                )
                println("[TEST] Add server exit code: ${addServer.exitCode}")
                println("[TEST] Add server stdout: ${addServer.stdout}")
                if (addServer.stderr.isNotBlank()) {
                    println("[TEST] Add server stderr: ${addServer.stderr}")
                }

                // List MCP servers
                val listResult = container.execInContainer(
                    "bash", "-c",
                    "$CLAUDE_BIN mcp list"
                )
                println("[TEST] MCP list exit code: ${listResult.exitCode}")
                println("[TEST] MCP list stdout: ${listResult.stdout}")
                if (listResult.stderr.isNotBlank()) {
                    println("[TEST] MCP list stderr: ${listResult.stderr}")
                }

                assertTrue(
                    "Should list intellij-steroid server. Output: ${listResult.stdout}",
                    listResult.stdout.contains("intellij-steroid")
                )
            } finally {
                container.stop()
            }
        }
    }

    /**
     * Tests that Claude CLI in Docker can discover steroid_ tools via the MCP server.
     */
    fun testClaudeInDockerDiscoversSteroidTools(): Unit = timeoutRunBlocking(5.minutes) {
        withMcpServerInDocker { url ->
            println("[TEST] MCP Server URL: $url")

            val container = createClaudeContainer()

            try {
                container.start()
                println("[TEST] Container started: ${container.containerId}")

                // Add MCP server
                val addServer = container.execInContainer(
                    "bash", "-c",
                    "$CLAUDE_BIN mcp add --transport sse intellij-steroid '$url'"
                )
                println("[TEST] Add server exit code: ${addServer.exitCode}")

                // Run Claude to discover tools
                val result = container.execInContainer(
                    "bash", "-c",
                    "$CLAUDE_BIN --print 'List all MCP tools that start with steroid_ and print their names. Format: TOOL: [name]'"
                )

                println("[TEST] Claude exit code: ${result.exitCode}")
                println("[TEST] Claude stdout: ${result.stdout}")
                if (result.stderr.isNotBlank()) {
                    println("[TEST] Claude stderr: ${result.stderr}")
                }

                val output = result.stdout

                // Check if tools were found - fail if server isn't accessible
                if (output.contains("TOOLS_FOUND: 0") ||
                    output.contains("don't see any tools") ||
                    output.contains("don't have any tools")
                ) {
                    fail("Claude couldn't connect to MCP server or find steroid_ tools. Output: $output")
                }

                assertTrue(
                    "Should find steroid_ tools. Output: $output",
                    output.contains("steroid_") || output.contains("TOOL:")
                )

            } finally {
                container.stop()
            }
        }
    }

    // ==================== Helper Methods ====================

    private fun getApiKey(): String {
        System.getenv("ANTHROPIC_API_KEY")?.takeIf { it.isNotBlank() }?.let { return it }
        val anthropicFile = File(System.getenv("HOME"), ".anthropic")
        return anthropicFile.readText().trim()
    }
}
