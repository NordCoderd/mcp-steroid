/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.intellij.mcp

import com.intellij.testFramework.common.timeoutRunBlocking
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.io.File
import java.lang.ProcessBuilder.Redirect
import java.net.HttpURLConnection
import java.net.URI
import java.util.concurrent.TimeUnit
import kotlin.io.path.createTempDirectory
import kotlin.time.Duration.Companion.seconds

/**
 * Integration tests that run Claude Code CLI against the MCP server.
 *
 * Prerequisites:
 * - Claude Code CLI must be installed and available as 'claude' command
 * - ANTHROPIC_API_KEY must be available (either in ~/.anthropic or environment)
 *
 * These tests verify that Claude Code can:
 * - Connect to our MCP server
 * - Discover steroid_ tools
 * - Call the tools successfully
 *
 * Note: Uses JUnit 3 style with BasePlatformTestCase.
 */
class ClaudeCliIntegrationTest : BasePlatformTestCase() {

    /**
     * Tests that Claude Code can discover our steroid_ tools.
     *
     * Note: This test requires the MCP server's HTTP endpoint to be functional.
     * In unit test environments where the HTTP server is not available, this test will be skipped.
     */
    fun testClaudeCodeDiscoversSteroidTools(): Unit = timeoutRunBlocking(120.seconds) {
        if (!isClaudeCliAvailable()) {
            println("[TEST] Claude CLI not available, skipping integration test")
            return@timeoutRunBlocking
        }

        if (!isApiKeyAvailable()) {
            println("[TEST] ANTHROPIC_API_KEY not available, skipping integration test")
            return@timeoutRunBlocking
        }

        McpTestUtil.withMcpServer { port, sseUrl ->
            println("[TEST] MCP Server running on port: $port")
            println("[TEST] SSE URL: $sseUrl")

            // Check if SSE endpoint is actually accessible
            if (!isSseEndpointAccessible(port)) {
                println("[TEST] SSE endpoint not accessible, skipping Claude CLI test")
                println("[TEST] Note: Run against a live IntelliJ instance or use integration-test/manual-test.sh")
                return@withMcpServer
            }

            val result = runClaudeCodeWithMcp(
                port = port,
                prompt = """
                    You are testing an MCP server integration.

                    1. List all available MCP tools that start with "steroid_"
                    2. For each tool, print its name and a one-line description
                    3. Call steroid_list_projects and print the result

                    Be concise. Output format:
                    TOOLS_FOUND: [number]
                    TOOL: [name] - [description]
                    PROJECTS: [result]
                """.trimIndent(),
            )

            println("[TEST] Claude output:")
            println(result.output)

            if (result.exitCode != 0) {
                println("[TEST] Claude stderr: ${result.stderr}")
            }

            assertTrue(
                "Claude should find steroid_ tools. Output: ${result.output}",
                result.output.contains("steroid_") || result.output.contains("TOOLS_FOUND")
            )

            assertFalse(
                "Claude should find more than 0 tools. Output: ${result.output}",
                result.output.contains("**TOOLS_FOUND: 0**")
            )
        }
    }

    /**
     * Tests that Claude Code can call steroid_list_projects.
     *
     * Note: This test requires the MCP server's HTTP endpoint to be functional.
     * In unit test environments where the HTTP server is not available, this test will be skipped.
     */
    fun testClaudeCodeCallsListProjects(): Unit = timeoutRunBlocking(120.seconds) {
        if (!isClaudeCliAvailable()) {
            println("[TEST] Claude CLI not available, skipping integration test")
            return@timeoutRunBlocking
        }

        if (!isApiKeyAvailable()) {
            println("[TEST] ANTHROPIC_API_KEY not available, skipping integration test")
            return@timeoutRunBlocking
        }

        McpTestUtil.withMcpServer { port, _ ->
            println("[TEST] MCP Server running on port: $port")

            // Check if SSE endpoint is actually accessible
            if (!isSseEndpointAccessible(port)) {
                println("[TEST] SSE endpoint not accessible, skipping Claude CLI test")
                println("[TEST] Note: Run against a live IntelliJ instance or use integration-test/manual-test.sh")
                return@withMcpServer
            }

            val result = runClaudeCodeWithMcp(
                port = port,
                prompt = """
                    Call the steroid_list_projects MCP tool and print the raw JSON result.
                    Do not add any commentary, just print the result.
                """.trimIndent(),
            )

            println("[TEST] Claude output:")
            println(result.output)

            // The result should contain project information (at minimum the test project)
            assertTrue(
                "Should contain project information. Output: ${result.output}",
                result.output.contains("project") || result.output.contains("name")
            )
        }
    }

    /**
     * Tests that Claude Code can add and list MCP servers.
     * Uses `claude mcp add` and `claude mcp list` commands.
     */
    fun testClaudeMcpAddAndListServer(): Unit = timeoutRunBlocking(30.seconds) {
        if (!isClaudeCliAvailable()) {
            println("[TEST] Claude CLI not available, skipping integration test")
            return@timeoutRunBlocking
        }

        McpTestUtil.withMcpServer { port, _ ->
            println("[TEST] MCP Server running on port: $port")

            val tempDir = createTempDirectory("claude-mcp-list-test-").toFile()

            try {
                // Create config directory structure that claude expects
                val configDir = File(tempDir, ".config/claude")
                configDir.mkdirs()

                // Add server using claude mcp add
                val addProcess = ProcessBuilder(
                    "claude", "mcp", "add",
                    "--transport", "sse",
                    "intellij-steroid-test",
                    "http://127.0.0.1:$port/sse"
                )
                addProcess.directory(tempDir)
                addProcess.environment()["HOME"] = tempDir.absolutePath
                addProcess.redirectErrorStream(true)

                val addResult = addProcess.start()
                val addOutput = addResult.inputStream.bufferedReader().readText()
                val addExitCode = addResult.waitFor()

                println("[TEST] claude mcp add output:")
                println(addOutput)
                println("[TEST] Add exit code: $addExitCode")

                // Run claude mcp list
                val listProcess = ProcessBuilder("claude", "mcp", "list")
                listProcess.directory(tempDir)
                listProcess.environment()["HOME"] = tempDir.absolutePath
                listProcess.redirectErrorStream(true)

                val listResult = listProcess.start()
                val listOutput = listResult.inputStream.bufferedReader().readText()
                val listExitCode = listResult.waitFor()

                println("[TEST] claude mcp list output:")
                println(listOutput)
                println("[TEST] List exit code: $listExitCode")

                assertTrue(
                    "Should list intellij-steroid-test server. Output: $listOutput",
                    listOutput.contains("intellij-steroid-test")
                )
            } finally {
                tempDir.deleteRecursively()
            }
        }
    }

    // ==================== Helper Methods ====================

    private fun isSseEndpointAccessible(port: Int): Boolean {
        return try {
            val url = URI("http://localhost:$port/sse").toURL()
            val connection = url.openConnection() as HttpURLConnection
            connection.setRequestProperty("Accept", "text/event-stream")
            connection.connectTimeout = 2000
            connection.readTimeout = 2000
            connection.connect()
            val responseCode = connection.responseCode
            connection.disconnect()
            responseCode == 200
        } catch (e: Exception) {
            println("[TEST] SSE endpoint check failed: ${e.message}")
            false
        }
    }

    private fun isClaudeCliAvailable(): Boolean {
        return try {
            val process = ProcessBuilder("which", "claude")
                .redirectErrorStream(true)
                .start()
            val exitCode = process.waitFor()
            exitCode == 0
        } catch (e: Exception) {
            println("[TEST] Error checking for claude CLI: ${e.message}")
            false
        }
    }

    private fun isApiKeyAvailable(): Boolean {
        // Check environment variable
        if (System.getenv("ANTHROPIC_API_KEY")?.isNotBlank() == true) {
            return true
        }

        // Check ~/.anthropic file
        val anthropicFile = File(System.getenv("HOME"), ".anthropic")
        return anthropicFile.exists() && anthropicFile.readText().trim().isNotBlank()
    }

    private fun getApiKey(): String {
        // Check environment variable first
        System.getenv("ANTHROPIC_API_KEY")?.takeIf { it.isNotBlank() }?.let { return it }

        // Fall back to ~/.anthropic file
        val anthropicFile = File(System.getenv("HOME"), ".anthropic")
        return anthropicFile.readText().trim()
    }

    data class ClaudeResult(
        val exitCode: Int,
        val output: String,
        val stderr: String
    )

    private fun runClaudeCodeWithMcp(
        port: Int,
        prompt: String,
    ): ClaudeResult {
        val tempDir = createTempDirectory("claude-test-").toFile()

        // Create .mcp.json configuration for this test
        val mcpConfig = File(tempDir, ".mcp.json")
        mcpConfig.writeText(
            """
                {
                  "mcpServers": {
                    "intellij-steroid": {
                      "type": "sse",
                      "url": "http://127.0.0.1:$port/sse"
                    }
                  }
                }
                """.trimIndent()
        )

        println("[TEST] Created MCP config at: ${mcpConfig.absolutePath}")
        println("[TEST] Config contents: ${mcpConfig.readText()}")

        // Run Claude Code with --print flag for non-interactive mode
        val processBuilder = ProcessBuilder(
            "claude",
            "--print",
            prompt
        )
        processBuilder.redirectInput(Redirect.PIPE)
        processBuilder.redirectOutput(Redirect.PIPE)
        processBuilder.redirectError(Redirect.PIPE)

        processBuilder.directory(tempDir)
        processBuilder.environment()["HOME"] = tempDir.absolutePath
        processBuilder.environment()["ANTHROPIC_API_KEY"] = getApiKey()

        val process = processBuilder.start()
        process.outputStream.close()

        val outputBuilder = StringBuilder()
        val errorBuilder = StringBuilder()

        val outputThread = Thread {
            process.inputStream.reader().forEachLine { line ->
                println("[TEST] Claude output: $line")
                outputBuilder.appendLine(line)
            }
        }
        val errorThread = Thread {
            process.errorStream.reader().forEachLine { line ->
                println("[TEST] Claude error: $line")
                errorBuilder.appendLine(line)
            }
        }

        outputThread.start()
        errorThread.start()

        val timeoutSeconds = 60L
        val completed = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)

        if (!completed) {
            process.destroyForcibly()
            return ClaudeResult(
                exitCode = -1,
                output = outputBuilder.toString(),
                stderr = "Process timed out after ${timeoutSeconds}s\n${errorBuilder}"
            )
        }

        outputThread.join(1000)
        errorThread.join(1000)

        return ClaudeResult(
            exitCode = process.exitValue(),
            output = outputBuilder.toString(),
            stderr = errorBuilder.toString()
        )
    }
}
