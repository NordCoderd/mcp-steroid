/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.integration

import com.jonnyzzz.mcpSteroid.testHelper.AiAgentSession
import com.jonnyzzz.mcpSteroid.testHelper.CloseableStackHost
import com.jonnyzzz.mcpSteroid.testHelper.assertExitCode
import com.jonnyzzz.mcpSteroid.testHelper.assertOutputContains
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import java.util.concurrent.TimeUnit
import java.util.stream.Stream

/**
 * Integration test: debugger demo from ai-tests/07-debugger.md.
 *
 * Runs AI agents inside a Docker container with IntelliJ IDEA + MCP Steroid plugin.
 * The agent is asked to debug DemoByJonnyzzz.kt and find the sortedByDescending bug.
 *
 * The container is kept alive after the test for debugging (removed on next run).
 * Video is always recorded and mounted to the host for live preview.
 */
class DebuggerDemoTest {

    @MethodSource("agents")
    @ParameterizedTest(name = "{0}")
    @Timeout(value = 20, unit = TimeUnit.MINUTES)
    fun `agent finds sortedByDescending bug via debugger`(agentName: String, agent: AiAgentSession) {
        val prompt = buildString {
            appendLine("Debug the file DemoByJonnyzzz.kt in this project to find the bug in the leaderboard function.")
            appendLine()
            appendLine("Requirements:")
            appendLine("1. Find and open DemoByJonnyzzz.kt in the project")
            appendLine("2. Set a breakpoint at the sortedByDescending line (line 7)")
            appendLine("3. Create a run configuration for DemoByJonnyzzzKt and start the debugger")
            appendLine("4. Wait for the debugger to suspend at the breakpoint")
            appendLine("5. Evaluate variables and expressions to understand the bug")
            appendLine("6. Step over the line and observe what happens")
            appendLine("7. Identify the root cause of the bug")
            appendLine()
            appendLine("Use ONLY steroid_execute_code (no screenshots or UI interaction).")
            appendLine()
            appendLine("At the end of your analysis, output these markers on separate lines:")
            appendLine("BUG_FOUND: yes")
            appendLine("ROOT_CAUSE: <one line description>")
        }

        val result = agent.runPrompt(prompt, timeoutSeconds = 600)

        // Agent must exit successfully
        result.assertExitCode(0, message = "debugger demo")

        val combined = result.output + "\n" + result.stderr

        // Agent must have used MCP Steroid execute_code tool
        result.assertOutputContains("steroid_execute_code", message = "agent must use steroid_execute_code")

        // Agent must mention sortedByDescending in its analysis
        result.assertOutputContains("sortedByDescending", message = "agent must mention sortedByDescending")

        // Agent must identify the root cause: sortedByDescending returns a new list
        // but the return value is ignored
        val rootCausePatterns = listOf(
            "ignor", "unused", "discard", "new list", "does not modify",
            "return value", "not assigned", "not used", "immutable",
        )
        val foundRootCause = rootCausePatterns.any { pattern ->
            combined.contains(pattern, ignoreCase = true)
        }
        check(foundRootCause) {
            "Agent did not identify the root cause (ignored return value).\n" +
                    "Expected one of: $rootCausePatterns\nOutput:\n$combined"
        }

        println("[TEST] Agent '$agentName' successfully identified the sortedByDescending bug")
    }

    companion object {
        @JvmStatic
        val lifetime by lazy {
            CloseableStackHost()
        }

        val session by lazy {
            IdeContainer.create(
                lifetime,
                "ide-agent",
            )
        }

        @JvmStatic
        fun agents(): Stream<Arguments> = session
            .aiAgentDriver
            .aiAgents
            .entries.stream()
            .map { (name, driver) ->
                Arguments.of(name, driver)
            }

        @JvmStatic
        @BeforeAll
        fun beforeAll() {
            // Trigger session creation (IDE start, MCP readiness)
            // The aiAgents lazy property will also call waitForMcpReady()
            session.toString()
        }

        @JvmStatic
        @AfterAll
        fun tearDown() {
            lifetime.closeAllStacks()
        }
    }
}
