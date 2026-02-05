/**
 * Test: Find Recent Test Run
 *
 * This example shows how to find the most recent test execution
 * from all available run content descriptors.
 *
 * IntelliJ API used:
 * - RunContentManager - Access all run content
 * - RunContentDescriptor - Execution handle
 * - SMTRunnerConsoleView - Test console detection
 *
 * Output: List of recent test executions
 */

import com.intellij.execution.ui.RunContentManager
import com.intellij.execution.testframework.sm.runner.ui.SMTRunnerConsoleView

val manager = RunContentManager.getInstance(project)
val allDescriptors = manager.allDescriptors

if (allDescriptors.isEmpty()) {
    println("No executions found")
    return
}

// Filter for test executions
val testDescriptors = allDescriptors.filter { descriptor ->
    descriptor.executionConsole is SMTRunnerConsoleView
}

if (testDescriptors.isEmpty()) {
    println("No test executions found")
    println()
    println("Available executions:")
    allDescriptors.forEach { descriptor ->
        val consoleType = descriptor.executionConsole?.javaClass?.simpleName ?: "null"
        val isTerminated = descriptor.processHandler?.isProcessTerminated ?: false
        val status = if (isTerminated) "✓" else "→"
        println("  $status ${descriptor.displayName} ($consoleType)")
    }
    return
}

// Print test executions
println("Test Executions (${testDescriptors.size}):")
println("═══════════════════════════════════════")
println()

testDescriptors.forEachIndexed { index, descriptor ->
    val handler = descriptor.processHandler
    val isTerminated = handler?.isProcessTerminated ?: false
    val isTerminating = handler?.isProcessTerminating ?: false

    val status = when {
        isTerminated -> "✓"
        isTerminating -> "⧗"
        else -> "→"
    }

    println("${index + 1}. $status ${descriptor.displayName}")

    // Show process status
    val statusStr = when {
        isTerminated -> {
            val exitCode = handler?.exitCode ?: -1
            "Completed (exit code: $exitCode)"
        }
        isTerminating -> "Terminating..."
        else -> "Running..."
    }
    println("   Status: $statusStr")

    // Show test results if available and completed
    if (isTerminated) {
        val console = descriptor.executionConsole as? SMTRunnerConsoleView
        if (console != null) {
            try {
                val resultsForm = console.resultsViewer
                val rootProxy = resultsForm.testsRootNode
                val allTests = rootProxy.allTests

                val passed = allTests.count { it.isPassed }
                val failed = allTests.count { it.isDefect }
                val ignored = allTests.count { it.isIgnored }

                println("   Results: $passed passed, $failed failed, $ignored ignored")
                println("   Duration: ${rootProxy.duration}ms")
            } catch (e: Exception) {
                println("   Results: Not available")
            }
        }
    }

    println()
}

println("═══════════════════════════════════════")

// Show most recent
val mostRecent = testDescriptors.lastOrNull()
if (mostRecent != null) {
    println("Most recent: ${mostRecent.displayName}")

    val handler = mostRecent.processHandler
    if (handler?.isProcessTerminated == true) {
        println("✓ Execution completed - ready to inspect results")
    } else {
        println("→ Execution in progress - wait for completion")
    }
}

/**
 * ## See Also
 *
 * Related test examples:
 * - [Run Tests](mcp-steroid://test/run-tests) - Execute test configuration
 * - [Wait for Completion](mcp-steroid://test/wait-for-completion) - Poll test status
 * - [Inspect Test Results](mcp-steroid://test/inspect-test-results) - Access results
 * - [List Run Configurations](mcp-steroid://test/list-run-configurations) - Discover available tests
 * - [Test Tree Navigation](mcp-steroid://test/test-tree-navigation) - Navigate test hierarchy
 *
 * Related IDE operations:
 * - [Run Configuration](mcp-steroid://ide/run-configuration) - List and execute run configs
 *
 * Overview resources:
 * - [Test Examples Overview](mcp-steroid://test/overview) - Complete test execution guide
 * - [Test Runner Skill Guide](mcp-steroid://skill/test-runner-guide) - Essential test knowledge
 */
