/**
 * Test: Test Tree Navigation
 *
 * This example shows how to navigate the test tree structure,
 * including test suites and individual tests.
 *
 * IntelliJ API used:
 * - SMTestProxy - Test tree node
 * - AbstractTestProxy - Base class with navigation methods
 *
 * Output: Hierarchical test tree with status indicators
 */

import com.intellij.execution.ui.RunContentManager
import com.intellij.execution.testframework.sm.runner.ui.SMTRunnerConsoleView
import com.intellij.execution.testframework.AbstractTestProxy

execute {
    // Recursive function to print test tree
    fun printTestTree(proxy: AbstractTestProxy, indent: String = "", isLast: Boolean = true) {
        // Determine status icon
        val status = when {
            proxy.isPassed -> "✓"
            proxy.isDefect -> "✗"
            proxy.isIgnored -> "○"
            proxy.isInProgress -> "→"
            else -> "?"
        }

        // Determine tree line characters
        val branch = if (indent.isEmpty()) "" else if (isLast) "└─ " else "├─ "

        // Print test node
        val duration = proxy.duration
        val durationStr = if (duration > 0) " (${duration}ms)" else ""
        println("$indent$branch$status ${proxy.name}$durationStr")

        // Recurse into children
        val children = proxy.children
        if (children.isNotEmpty()) {
            val childIndent = indent + if (isLast) "   " else "│  "
            children.forEachIndexed { index, child ->
                val childIsLast = index == children.size - 1
                printTestTree(child, childIndent, childIsLast)
            }
        }
    }

    // Get test results
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

    // Print tree
    println("Test Tree: ${descriptor.displayName}")
    println("═══════════════════════════════════════")
    println()

    printTestTree(rootProxy)

    println()
    println("═══════════════════════════════════════")
    println("Legend:")
    println("  ✓ = Passed")
    println("  ✗ = Failed")
    println("  ○ = Ignored")
    println("  → = In Progress")
}

/**
 * ## See Also
 *
 * Related test examples:
 * - [Run Tests](mcp-steroid://test/run-tests) - Execute test configuration
 * - [Wait for Completion](mcp-steroid://test/wait-for-completion) - Poll test status
 * - [Inspect Test Results](mcp-steroid://test/inspect-test-results) - Access results
 * - [Test Failure Details](mcp-steroid://test/test-failure-details) - Access failure messages
 * - [Test Statistics](mcp-steroid://test/test-statistics) - Get test counts
 *
 * Related IDE operations:
 * - [Run Configuration](mcp-steroid://ide/run-configuration) - List and execute run configs
 *
 * Overview resources:
 * - [Test Examples Overview](mcp-steroid://test/overview) - Complete test execution guide
 * - [Test Runner Skill Guide](mcp-steroid://skill/test-runner-guide) - Essential test knowledge
 */
