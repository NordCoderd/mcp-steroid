/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.intellij.mcp

import com.intellij.testFramework.common.timeoutRunBlocking
import org.junit.Ignore
import kotlin.time.Duration.Companion.seconds

/**
 * Integration tests for Gemini CLI with MCP server.
 *
 * Prerequisites:
 * - Docker must be installed and running
 * - GEMINI_API_KEY must be available (either in env or ~/.vertex)
 *
 * KNOWN ISSUE: Gemini CLI v0.22.2 has a bug where `gemini mcp add --transport http`
 * creates a settings.json with `"type": "http"` key, but then fails to read it with
 * "Unrecognized key(s) in object: 'type'" error (exit code 52).
 *
 * The tests that require MCP are disabled until this is fixed upstream.
 * See: https://github.com/google-gemini/gemini-cli/issues/15449
 */
@Ignore("Disabled: Gemini CLI has a bug with HTTP MCP transport - https://github.com/google-gemini/gemini-cli/issues/15449")
abstract class CliGeminiIntegrationTest : CliIntegrationTestBase() {
    private fun geminiSession() = DockerGeminiSession.create(testRootDisposable)

    override fun newAiSession(): AiAgentSession = geminiSession().registerMcp(resolveDockerUrl(), "intellij")

    fun testGeminiInstalled(): Unit = timeoutRunBlocking(180.seconds) {
        geminiSession()
            .runInContainer("--version")
            .assertExitCode(0, "Gemini")
    }

    fun testMcpServerRegistration() {
        val mcpName = "intellij"
        timeoutRunBlocking(180.seconds) {
            val session = geminiSession()
            session.registerMcp(resolveDockerUrl(), mcpName)
            session.session.runInContainer("cat", "/home/gemini/work/.gemini/settings.json")
            session.runInContainer("mcp", "list", )
                .assertExitCode(0, "mcp list should succeed")
                .assertOutputContains(mcpName, message = "mcp list should contain registered server")
        }
    }
}
