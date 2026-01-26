/**
 * Test: Test Statistics
 *
 * This example shows how to get test counts and statistics
 * from test execution results.
 *
 * IntelliJ API used:
 * - SMTestProxy - Access test counts and statistics
 * - AbstractTestProxy - Duration and test collection methods
 *
 * Output: Detailed test statistics
 */

import com.intellij.execution.ui.RunContentManager
import com.intellij.execution.testframework.sm.runner.ui.SMTRunnerConsoleView

execute {
    val manager = RunContentManager.getInstance(project)
    val descriptor = manager.allDescriptors.lastOrNull()

    if (descriptor == null) {
        println("No test execution found")
        return@execute
    }

    val console = descriptor.executionConsole as? SMTRunnerConsoleView
    if (console == null) {
        println("Not a test execution")
        return@execute
    }

    val resultsForm = console.resultsViewer
    val rootProxy = resultsForm.testsRootNode

    // Collect all tests
    val allTests = rootProxy.allTests

    // Calculate statistics
    val total = allTests.size
    val passed = allTests.count { it.isPassed }
    val failed = allTests.count { it.isDefect }
    val ignored = allTests.count { it.isIgnored }
    val inProgress = allTests.count { it.isInProgress }

    // Calculate durations
    val totalDuration = rootProxy.duration
    val avgDuration = if (total > 0) totalDuration / total else 0L
    val maxDuration = allTests.maxOfOrNull { it.duration } ?: 0L
    val minDuration = allTests.filter { it.duration > 0 }.minOfOrNull { it.duration } ?: 0L

    // Count test suites vs leaf tests
    val suites = allTests.count { it.children.isNotEmpty() }
    val leafTests = allTests.count { it.children.isEmpty() }

    // Calculate pass rate
    val passRate = if (total > 0) (passed * 100.0 / total) else 0.0

    // Print statistics
    println("Test Statistics: ${descriptor.displayName}")
    println("═══════════════════════════════════════")
    println()

    println("Test Counts:")
    println("  Total Tests:    $total")
    println("  ✓ Passed:       $passed (${String.format("%.1f", passRate)}%)")
    println("  ✗ Failed:       $failed")
    println("  ○ Ignored:      $ignored")
    println("  → In Progress:  $inProgress")
    println()

    println("Test Structure:")
    println("  Test Suites:    $suites")
    println("  Leaf Tests:     $leafTests")
    println()

    println("Execution Times:")
    println("  Total Duration: ${totalDuration}ms")
    println("  Average:        ${avgDuration}ms")
    println("  Max:            ${maxDuration}ms")
    println("  Min:            ${minDuration}ms")
    println()

    // Find slowest tests
    val slowestTests = allTests
        .filter { it.children.isEmpty() }  // Leaf tests only
        .sortedByDescending { it.duration }
        .take(5)

    if (slowestTests.isNotEmpty()) {
        println("Slowest Tests:")
        slowestTests.forEach { test ->
            val status = when {
                test.isPassed -> "✓"
                test.isIgnored -> "○"
                test.isDefect -> "✗"
                else -> "?"
            }
            println("  $status ${test.name} - ${test.duration}ms")
        }
        println()
    }

    // Overall result
    println("═══════════════════════════════════════")
    when {
        failed > 0 -> println("✗ Build FAILED ($failed failed test${if (failed > 1) "s" else ""})")
        ignored > 0 -> println("○ Build PASSED ($ignored test${if (ignored > 1) "s" else ""} ignored)")
        else -> println("✓ Build PASSED (all tests passed)")
    }
}

/**
 * ## See Also
 *
 * Related test examples:
 * - [Run Tests](mcp-steroid://test/run-tests) - Execute test configuration
 * - [Wait for Completion](mcp-steroid://test/wait-for-completion) - Poll test status
 * - [Inspect Test Results](mcp-steroid://test/inspect-test-results) - Access results
 * - [Test Tree Navigation](mcp-steroid://test/test-tree-navigation) - Navigate test hierarchy
 * - [Test Failure Details](mcp-steroid://test/test-failure-details) - Access failure messages
 *
 * Related IDE operations:
 * - [Run Configuration](mcp-steroid://ide/run-configuration) - List and execute run configs
 *
 * Overview resources:
 * - [Test Examples Overview](mcp-steroid://test/overview) - Complete test execution guide
 * - [Test Runner Skill Guide](mcp-steroid://skill/test-runner-guide) - Essential test knowledge
 */
