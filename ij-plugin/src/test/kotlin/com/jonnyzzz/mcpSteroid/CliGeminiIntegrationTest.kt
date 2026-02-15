/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid

import com.intellij.testFramework.common.timeoutRunBlocking
import com.jonnyzzz.mcpSteroid.testHelper.AiAgentSession
import com.jonnyzzz.mcpSteroid.testHelper.DockerGeminiSession
import com.jonnyzzz.mcpSteroid.testHelper.assertExitCode
import com.jonnyzzz.mcpSteroid.testHelper.assertOutputContains
import kotlin.time.Duration.Companion.seconds

/**
 * Integration tests for Gemini CLI with MCP server.
 *
 * Prerequisites:
 * - Docker must be installed and running
 * - GEMINI_API_KEY must be available (either in env or ~/.vertex)
 */
class CliGeminiIntegrationTest : CliIntegrationTestBase() {
    private fun geminiSession() = DockerGeminiSession.create(lifetime)

    override fun createAiSession(): AiAgentSession = geminiSession()

    fun testGeminiInstalled(): Unit = timeoutRunBlocking(180.seconds) {
        geminiSession()
            .runInContainer("--version")
            .assertExitCode(0, "Gemini")
    }

    fun testMcpServerRegistration() {
        val mcpName = "intellij"
        timeoutRunBlocking(180.seconds) {
            val session = geminiSession()
            session.registerHttpMcp(resolveDockerUrl(), mcpName)
            session.runInContainer("mcp", "list", )
                .assertExitCode(0, "mcp list should succeed")
                .assertOutputContains(mcpName, message = "mcp list should contain registered server")
        }
    }


    override fun testDiscoversSteroidTools() {
        //needed to make test runner work
        super.testDiscoversSteroidTools()
    }

    override fun testSystemPropertyCanBeRead() {
        //needed to make test runner work
        super.testSystemPropertyCanBeRead()
    }

    override fun testDiscoversSteroidToolsViaNpx() {
        //needed to make test runner work
        super.testDiscoversSteroidToolsViaNpx()
    }

    override fun testSystemPropertyCanBeReadViaNpx() {
        //needed to make test runner work
        super.testSystemPropertyCanBeReadViaNpx()
    }

    override fun testExecSessionReset() {
        //needed to make test runner work
        super.testExecSessionReset()
    }
}
