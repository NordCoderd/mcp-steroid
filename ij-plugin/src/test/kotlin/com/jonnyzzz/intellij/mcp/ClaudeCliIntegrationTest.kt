/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.intellij.mcp

import com.intellij.testFramework.common.timeoutRunBlocking
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.ktor.server.util.url
import java.io.File
import java.lang.ProcessBuilder.Redirect
import java.util.concurrent.TimeUnit
import kotlin.io.path.createTempDirectory
import kotlin.time.Duration.Companion.seconds

/**
 * Integration tests that run Claude Code CLI against the MCP server.
 *
 * Prerequisites:
 * - Claude Code CLI must be installed and available as 'claude' command
 * - ANTHROPIC_API_KEY must be available (either in ~/.anthropic or environment)
 * - MCP server must be running (use ./gradlew runIde)
 *
 * These tests are designed to be run manually against a live IDE instance.
 * They will be skipped automatically in automated test runs.
 */
class ClaudeCliIntegrationTest : BasePlatformTestCase() {

    /**
     * Tests that Claude Code can discover our steroid_ tools.
     * This test requires a live MCP server running.
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

        val sseUrl = McpTestUtil.getSseUrlIfRunning()
        println("[TEST] SSE URL: $sseUrl")

        val result = runClaudeCodeWithMcp(
            url = sseUrl,
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

        assertFalse("",
            result.output.contains("TOOLS_FOUND: 0"))

        assertTrue(
            "Claude should find steroid_ tools. Output: ${result.output}",
            result.output.contains("steroid_") || result.output.contains("TOOLS_FOUND")
        )
    }

    // ==================== Helper Methods ====================

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
        if (System.getenv("ANTHROPIC_API_KEY")?.isNotBlank() == true) {
            return true
        }
        val anthropicFile = File(System.getenv("HOME"), ".anthropic")
        return anthropicFile.exists() && anthropicFile.readText().trim().isNotBlank()
    }

    private fun getApiKey(): String {
        System.getenv("ANTHROPIC_API_KEY")?.takeIf { it.isNotBlank() }?.let { return it }
        val anthropicFile = File(System.getenv("HOME"), ".anthropic")
        return anthropicFile.readText().trim()
    }

    data class ClaudeResult(
        val exitCode: Int,
        val output: String,
        val stderr: String
    )

    private fun runClaudeCodeWithMcp(
        url: String,
        prompt: String,
    ): ClaudeResult {
        val tempDir = createTempDirectory("claude-test-").toFile()

        val mcpConfig = File(tempDir, ".mcp.json")
        mcpConfig.writeText(
            """
                {
                  "mcpServers": {
                    "intellij-steroid": {
                      "type": "sse",
                      "url": "$url"
                    }
                  }
                }
                """.trimIndent()
        )

        println("[TEST] Created MCP config at: ${mcpConfig.absolutePath}")

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
                outputBuilder.appendLine(line)
            }
        }
        val errorThread = Thread {
            process.errorStream.reader().forEachLine { line ->
                errorBuilder.appendLine(line)
            }
        }

        outputThread.start()
        errorThread.start()

        val completed = process.waitFor(60L, TimeUnit.SECONDS)

        if (!completed) {
            process.destroyForcibly()
            return ClaudeResult(-1, outputBuilder.toString(), "Timeout\n$errorBuilder")
        }

        outputThread.join(1000)
        errorThread.join(1000)

        return ClaudeResult(process.exitValue(), outputBuilder.toString(), errorBuilder.toString())
    }
}
