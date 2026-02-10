/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.integration.arena

import com.jonnyzzz.mcpSteroid.integration.IdeContainer
import com.jonnyzzz.mcpSteroid.integration.create
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
 * Integration test that runs dpaia.dev arena test cases.
 *
 * Each test case:
 * 1. Clones a repository from the dpaia dataset inside the Docker container
 * 2. Checks out the specified base commit
 * 3. Applies the test patch (failing tests that define expected behavior)
 * 4. Asks an AI agent to fix the code via MCP Steroid
 * 5. Evaluates whether the agent engaged meaningfully
 *
 * The dataset is downloaded from:
 * https://github.com/dpaia/ee-dataset/blob/main/datasets/java-spring-ee-dataset.json
 *
 * To run a specific test case, set the system property:
 *   -Darena.test.instanceId=dpaia__empty__maven__springboot3-3
 *
 * To run only tests with a specific tag:
 *   -Darena.test.tag=JPA
 *
 * To limit the number of test cases (for CI):
 *   -Darena.test.maxCases=3
 */
class DpaiaArenaTest {

    @MethodSource("arenaTestCases")
    @ParameterizedTest(name = "[{0}] {1}")
    @Timeout(value = 30, unit = TimeUnit.MINUTES)
    fun `arena test case`(agentName: String, testCaseId: String, agent: AiAgentSession, testCase: DpaiaTestCase) {
        val runner = ArenaTestRunner(
            container = session.scope,
            projectGuestDir = ARENA_WORKSPACE,
        )

        val result = runner.runTest(
            testCase = testCase,
            agent = agent,
            timeoutSeconds = 900,
        )

        // Basic assertions: agent ran successfully and used MCP Steroid
        result.agentResult.assertExitCode(0, message = "arena test ${testCase.instanceId}")
        result.agentResult.assertOutputContains(
            "steroid_execute_code",
            message = "agent must use steroid_execute_code for ${testCase.instanceId}"
        )

        println("[ARENA] Test ${testCase.instanceId} completed with agent '$agentName'")
        println("[ARENA]   Claimed fix: ${result.evaluation.agentClaimedFix}")
        println("[ARENA]   Summary: ${result.evaluation.agentSummary ?: "(none)"}")
    }

    companion object {
        /** Dataset URL */
        private const val DATASET_URL =
            "https://raw.githubusercontent.com/dpaia/ee-dataset/main/datasets/java-spring-ee-dataset.json"

        /** Container directory for cloned arena projects */
        private const val ARENA_WORKSPACE = "/home/agent/arena-projects"

        /**
         * Default test case to run when no system property is set.
         * This is a simple JPA entity test from a small Spring Boot project.
         */
        private const val DEFAULT_INSTANCE_ID = "dpaia__empty__maven__springboot3-3"

        @JvmStatic
        val lifetime by lazy { CloseableStackHost() }

        val session by lazy {
            IdeContainer.create(
                lifetime,
                "ide-agent",
                runId = "dpaia-arena",
            )
        }

        private val dataset by lazy {
            println("[ARENA] Downloading dataset from $DATASET_URL ...")
            val cases = DpaiaDatasetLoader.loadFromUrl(DATASET_URL)
            println("[ARENA] Loaded ${cases.size} test cases")
            cases
        }

        @JvmStatic
        fun arenaTestCases(): Stream<Arguments> {
            // Trigger session so agents are initialized
            session.toString()

            val selectedCases = selectTestCases()
            println("[ARENA] Selected ${selectedCases.size} test case(s) for execution")

            val agents = session.aiAgentDriver.aiAgents

            return selectedCases.flatMap { testCase ->
                agents.map { (agentName, agentSession) ->
                    Arguments.of(agentName, testCase.instanceId, agentSession, testCase)
                }
            }.stream()
        }

        private fun selectTestCases(): List<DpaiaTestCase> {
            // Option 1: Specific instance ID via system property (defaults to DEFAULT_INSTANCE_ID)
            val specificId = System.getProperty("arena.test.instanceId") ?: DEFAULT_INSTANCE_ID
            if (System.getProperty("arena.test.tag") == null && System.getProperty("arena.test.maxCases") == null) {
                return listOf(DpaiaDatasetLoader.findById(dataset, specificId))
            }

            // Option 2: Filter by tag
            val tag = System.getProperty("arena.test.tag")
            val filtered = if (tag != null) {
                DpaiaDatasetLoader.filterByTag(dataset, tag)
            } else {
                dataset
            }

            // Option 3: Limit number of cases
            val maxCases = System.getProperty("arena.test.maxCases")?.toIntOrNull() ?: 1

            return filtered.take(maxCases)
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
