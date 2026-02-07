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
        agent.runPrompt("Tell me what MCP Servers and what methods you see. Clearly say if you CANNOT SEE that")
            .assertExitCode(0)
            .assertOutputContains("execute_code")
            .assertNoErrorsInOutput("Must be no errors")
            .assertNoMessageInOutput("I cannot see")
    }

    @MethodSource("agents")
    @ParameterizedTest(name = "{0}")
    fun checkWhatYouSee(agentName: String, agent: AiAgentSession) {
        agent.runPrompt("Tell me what you see in my IntelliJ IDEA. Clearly say if you CANNOT SEE that")
            .assertExitCode(0)
            .assertNoErrorsInOutput("Must be no errors")
            .assertNoMessageInOutput("I cannot see")
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
            session.toString()
        }

        @JvmStatic
        @AfterAll
        fun tearDown() {
            lifetime.closeAllStacks()
        }
    }
}
