/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.integration

import com.jonnyzzz.mcpSteroid.testHelper.*
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import java.util.stream.Stream

class WhatYouSeeTest {
    @MethodSource("agents")
    @ParameterizedTest(name = "{0}")
    fun describeMcp(agentName: String, agent: AiAgentSession) {
        val result = agent.runPrompt(
            "List all MCP tools you have access to. " +
                    "For each tool, print its exact name on a separate line. " +
                    "If you have no MCP tools, respond with the word NO_MCP_TOOLS_FOUND.",
            timeoutSeconds = 180
        )
            .assertExitCode(0)
            .assertNoErrorsInOutput("describeMcp must have no errors")
            .assertNoMessageInOutput("NO_MCP_TOOLS_FOUND")

        // Verify the agent can see the core MCP Steroid tools
        result.assertOutputContains("execute_code")
        result.assertOutputContains("list_projects")
        result.assertOutputContains("take_screenshot")
    }

    @MethodSource("agents")
    @ParameterizedTest(name = "{0}")
    fun checkWhatYouSee(agentName: String, agent: AiAgentSession) {
        agent.runPrompt(
            "Describe the current state of the IntelliJ IDEA IDE. " +
                    "Mention the project name if visible. " +
                    "If you cannot access IDE information, respond with the word NO_IDE_ACCESS.",
            timeoutSeconds = 180
        )
            .assertExitCode(0)
            .assertNoErrorsInOutput("checkWhatYouSee must have no errors")
            .assertNoMessageInOutput("NO_IDE_ACCESS")
    }

    @MethodSource("agents")
    @ParameterizedTest(name = "{0}")
    fun executeCodeViaMcp(agentName: String, agent: AiAgentSession) {
        val result = agent.runPrompt(
            "Use the steroid_execute_code tool to run this Kotlin code: println(\"MCP_STEROID_WORKS\") " +
                    "Show the output of the execution. " +
                    "If the code execution fails, respond with the word CODE_EXECUTION_FAILED.",
            timeoutSeconds = 180
        )
            .assertExitCode(0)
            .assertNoErrorsInOutput("executeCodeViaMcp must have no errors")
            .assertNoMessageInOutput("CODE_EXECUTION_FAILED")

        result.assertOutputContains("MCP_STEROID_WORKS")
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
