/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.integration

import com.jonnyzzz.mcpSteroid.testHelper.CloseableStackHost
import com.jonnyzzz.mcpSteroid.testHelper.assertExitCode
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import java.util.concurrent.TimeUnit
import java.util.stream.Stream

class WhatYouSeeTest {

    @ParameterizedTest(name = "{0}")
    @MethodSource("agents")
    @Timeout(value = 10, unit = TimeUnit.MINUTES)
    fun checkWhatYouSee(agentName: String) {
        val agent = session.aiAgentDriver.aiAgents[agentName]
            ?: error("Agent '$agentName' not found in aiAgents")
        agent.runPrompt("Tell me what you see in my IntelliJ IDEA. Clearly say I CANNOT SEE if you cannot see")
            .assertExitCode(0)
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
        fun agents(): Stream<Arguments> =
            session.aiAgentDriver.aiAgents.keys.stream().map { Arguments.of(it) }

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
