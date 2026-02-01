/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.server

import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * Integration tests that validate the test execution APIs documented in test-examples/.
 *
 * These tests verify that:
 * 1. All test example resources are correctly loaded and contain valid content
 * 2. The documented API patterns actually work in practice
 * 3. The workflow (list → run → wait → inspect) is correct
 *
 * This follows the test-first approach from CLAUDE.md:
 * - First, create a failing test that reproduces the expected behavior
 * - Then implement/fix to make the test pass
 * - Tests verify the fix works and prevent regression
 */
class TestExecutionApiTest : BasePlatformTestCase() {

    private val handler = TestExamplesResourceHandler()

    // ============================================================
    // Resource Loading Tests
    // ============================================================

    fun testOverviewResourceLoads() {
        val overview = handler.loadOverview()
        assertNotNull("Overview should not be null", overview)
        assertTrue("Overview should contain title", overview.contains("Test Execution Examples"))
        assertTrue("Overview should mention test execution", overview.contains("test execution"))
    }

    fun testAllExamplesAreDefined() {
        val examples = handler.examples
        assertEquals("Should have 9 test examples", 9, examples.size)

        val expectedIds = listOf(
            "list-run-configurations",
            "run-tests",
            "wait-for-completion",
            "inspect-test-results",
            "test-tree-navigation",
            "test-statistics",
            "test-failure-details",
            "find-recent-test-run",
            "demo-debug-test",
        )

        expectedIds.forEach { id ->
            assertTrue("Should have example: $id", examples.any { it.id == id })
        }
    }

    fun testListRunConfigurationsLoads() {
        val content = handler.loadExample("/test-examples/list-run-configurations.md")
        assertNotNull("Content should not be null", content)
        assertTrue("Should use RunManager", content.contains("RunManager"))
        assertTrue("Should list configurations", content.contains("allSettings"))
    }

    fun testRunTestsLoads() {
        val content = handler.loadExample("/test-examples/run-tests.md")
        assertNotNull("Content should not be null", content)
        assertTrue("Should use ProgramRunnerUtil", content.contains("ProgramRunnerUtil"))
        assertTrue("Should execute configuration", content.contains("executeConfiguration"))
    }

    fun testWaitForCompletionLoads() {
        val content = handler.loadExample("/test-examples/wait-for-completion.md")
        assertNotNull("Content should not be null", content)
        assertTrue("Should use RunContentManager", content.contains("RunContentManager"))
        assertTrue("Should check termination", content.contains("isProcessTerminated"))
    }

    fun testInspectTestResultsLoads() {
        val content = handler.loadExample("/test-examples/inspect-test-results.md")
        assertNotNull("Content should not be null", content)
        assertTrue("Should use SMTRunnerConsoleView", content.contains("SMTRunnerConsoleView"))
        assertTrue("Should access test results", content.contains("resultsViewer"))
    }

    fun testTestTreeNavigationLoads() {
        val content = handler.loadExample("/test-examples/test-tree-navigation.md")
        assertNotNull("Content should not be null", content)
        assertTrue("Should navigate test tree", content.contains("children"))
    }

    fun testTestStatisticsLoads() {
        val content = handler.loadExample("/test-examples/test-statistics.md")
        assertNotNull("Content should not be null", content)
        assertTrue("Should compute statistics", content.contains("count") || content.contains("size"))
    }

    fun testTestFailureDetailsLoads() {
        val content = handler.loadExample("/test-examples/test-failure-details.md")
        assertNotNull("Content should not be null", content)
        assertTrue("Should access failure details",
            content.contains("errorMessage") || content.contains("stacktrace"))
    }

    fun testFindRecentTestRunLoads() {
        val content = handler.loadExample("/test-examples/find-recent-test-run.md")
        assertNotNull("Content should not be null", content)
        assertTrue("Should find recent runs", content.contains("allDescriptors"))
    }

    // ============================================================
    // Structure Validation Tests
    // ============================================================

    fun testAllExamplesHaveRequiredStructure() {
        handler.examples.forEach { example ->
            val content = handler.loadExample(example.resourcePath)
            assertNotNull("${example.id} should load", content)


        }
    }

    fun testExampleDescriptionsAreUseful() {
        handler.examples.forEach { example ->
            assertTrue("${example.id} description should not be empty",
                example.description.isNotBlank())

            assertTrue("${example.id} description should have meaningful content (>50 chars)",
                example.description.length > 50)

            assertTrue("${example.id} description should mention test execution or results",
                example.description.contains("test", ignoreCase = true) ||
                    example.description.contains("run", ignoreCase = true) ||
                    example.description.contains("result", ignoreCase = true))
        }
    }

    // ============================================================
    // API Pattern Tests
    // ============================================================

    fun testListRunConfigurationsPattern() {
        val content = handler.loadExample("/test-examples/list-run-configurations.md")

        // Verify the pattern: RunManager.getInstance(project).allSettings
        assertTrue("Should get RunManager instance", content.contains("RunManager.getInstance(project)"))
        assertTrue("Should access allSettings", content.contains("allSettings"))

        // Verify output includes configuration details
        assertTrue("Should show configuration name", content.contains("configName") || content.contains("setting.name"))
        assertTrue("Should show configuration type", content.contains("type.displayName") || content.contains("typeName"))
    }

    fun testRunTestsPattern() {
        val content = handler.loadExample("/test-examples/run-tests.md")

        // Verify the pattern: find config, get executor, execute on EDT
        assertTrue("Should find configuration by name", content.contains("firstOrNull { it.name"))
        assertTrue("Should get executor", content.contains("ExecutorRegistry"))
        assertTrue("Should execute on EDT", content.contains("withContext(Dispatchers.EDT)"))
        assertTrue("Should call executeConfiguration", content.contains("executeConfiguration"))

        // Verify it references descriptor discovery
        assertTrue("Should mention RunContentDescriptor", content.contains("RunContentDescriptor"))
    }

    fun testWaitForCompletionPattern() {
        val content = handler.loadExample("/test-examples/wait-for-completion.md")

        // Verify the pattern: get all descriptors, check termination status
        assertTrue("Should get all descriptors", content.contains("allDescriptors"))
        assertTrue("Should check isProcessTerminated", content.contains("isProcessTerminated"))
        assertTrue("Should access process handler", content.contains("processHandler"))

        // Verify it handles both running and completed states
        assertTrue("Should handle completed state", content.contains("exitCode"))
    }

    fun testInspectTestResultsPattern() {
        val content = handler.loadExample("/test-examples/inspect-test-results.md")

        // Verify the pattern: descriptor → console → resultsViewer → rootProxy
        assertTrue("Should cast to SMTRunnerConsoleView", content.contains("SMTRunnerConsoleView"))
        assertTrue("Should get resultsViewer", content.contains("resultsViewer"))
        assertTrue("Should get testsRootNode", content.contains("testsRootNode"))

        // Verify it accesses test statistics
        assertTrue("Should count passed tests", content.contains("isPassed"))
        assertTrue("Should count failed tests", content.contains("isDefect"))
        assertTrue("Should access test properties", content.contains("allTests"))
    }

    fun testTestTreeNavigationPattern() {
        val content = handler.loadExample("/test-examples/test-tree-navigation.md")

        // Verify the pattern: recursive tree traversal
        assertTrue("Should access children", content.contains("children"))
        assertTrue("Should check test status",
            content.contains("isPassed") || content.contains("isDefect"))

        // Verify it shows tree structure
        assertTrue("Should show hierarchy", content.contains("indent") || content.contains("printTestTree"))
    }

    fun testFindRecentTestRunPattern() {
        val content = handler.loadExample("/test-examples/find-recent-test-run.md")

        // Verify the pattern: filter for test executions
        assertTrue("Should filter test descriptors", content.contains("filter"))
        assertTrue("Should check for SMTRunnerConsoleView", content.contains("SMTRunnerConsoleView"))
        assertTrue("Should get most recent", content.contains("lastOrNull"))
    }

    // ============================================================
    // Documentation Consistency Tests
    // ============================================================

    fun testOverviewListsAllExamples() {
        val overview = handler.loadOverview()

        // Verify overview mentions all example files
        handler.examples.forEach { example ->
            assertTrue("Overview should mention ${example.id}",
                overview.contains(example.id))
        }
    }

    fun testExamplesMatchOverviewWorkflow() {
        val overview = handler.loadOverview()

        // Verify overview describes the workflow
        assertTrue("Overview should describe workflow", overview.contains("workflow", ignoreCase = true))

        // Verify key API classes are documented
        assertTrue("Overview should mention RunManager", overview.contains("RunManager"))
        assertTrue("Overview should mention ProgramRunnerUtil", overview.contains("ProgramRunnerUtil"))
        assertTrue("Overview should mention SMTRunnerConsoleView", overview.contains("SMTRunnerConsoleView"))
        assertTrue("Overview should mention RunContentDescriptor", overview.contains("RunContentDescriptor"))
    }

    fun testExamplesUseConsistentImports() {
        handler.examples.forEach { example ->
            val content = handler.loadExample(example.resourcePath)

            // All examples should use explicit imports
            assertTrue("${example.id} should have import statements or use FQN",
                content.contains("import ") || !content.contains("RunManager"))
        }
    }

    fun testExamplesFollowThreadingRules() {
        // Verify examples that need EDT use withContext(Dispatchers.EDT)
        val runTestsContent = handler.loadExample("/test-examples/run-tests.md")
        assertTrue("run-tests should execute on EDT",
            runTestsContent.contains("withContext(Dispatchers.EDT)"))

        // Verify examples that poll don't block EDT
        val waitContent = handler.loadExample("/test-examples/wait-for-completion.md")
        assertFalse("wait-for-completion should not block EDT",
            waitContent.contains("withContext(Dispatchers.EDT)"))
    }

    // ============================================================
    // Cross-Reference Tests
    // ============================================================

    fun testExamplesReferenceRelatedExamples() {
        // run-tests should reference wait-for-completion
        val runTestsContent = handler.loadExample("/test-examples/run-tests.md")
        assertTrue("run-tests should mention wait-for-completion",
            runTestsContent.contains("wait-for-completion"))

        // wait-for-completion should reference inspect-test-results
        val waitContent = handler.loadExample("/test-examples/wait-for-completion.md")
        assertTrue("wait-for-completion should mention inspect-test-results",
            waitContent.contains("inspect-test-results"))
    }

    // ============================================================
    // API Completeness Tests
    // ============================================================

    fun testApiCoverageIsComplete() {
        val overview = handler.loadOverview()

        // Verify all key API classes are covered
        val requiredApis = listOf(
            "RunManager",
            "ProgramRunnerUtil",
            "ExecutorRegistry",
            "RunContentDescriptor",
            "RunContentManager",
            "SMTRunnerConsoleView",
            "SMTestRunnerResultsForm",
            "SMTestProxy",
            "SMRootTestProxy",
            "ProcessHandler",
        )

        requiredApis.forEach { api ->
            assertTrue("Overview should document $api", overview.contains(api))
        }
    }

    fun testWorkflowStepsAreComplete() {
        val overview = handler.loadOverview()

        // Verify the complete workflow is documented
        val workflowSteps = listOf(
            "List run configurations",
            "Execute test configuration",
            "Wait for",
            "Inspect",
            "Navigate test tree",
        )

        workflowSteps.forEach { step ->
            assertTrue("Overview should document: $step",
                overview.contains(step, ignoreCase = true))
        }
    }
}
