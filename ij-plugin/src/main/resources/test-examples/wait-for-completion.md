/**
 * Test: Wait for Completion
 *
 * This example polls the most recent test execution to check
 * if it has completed. Call this repeatedly until tests finish.
 *
 * IntelliJ API used:
 * - RunContentManager - Access all run content descriptors
 * - ProcessHandler - Check process termination status
 *
 * Output: Completion status and exit code
 */

import com.intellij.execution.ui.RunContentManager

execute {
    val manager = RunContentManager.getInstance(project)
    val allDescriptors = manager.allDescriptors

    if (allDescriptors.isEmpty()) {
        println("No test executions found")
        return@execute
    }

    // Get most recent descriptor
    val descriptor = allDescriptors.lastOrNull()
    if (descriptor == null) {
        println("No descriptor available")
        return@execute
    }

    val handler = descriptor.processHandler

    if (handler == null) {
        println("No process handler available")
        return@execute
    }

    // Check termination status
    val isTerminated = handler.isProcessTerminated
    val isTerminating = handler.isProcessTerminating

    println("Test Execution Status:")
    println("  Configuration: ${descriptor.displayName}")
    println("  Terminated: $isTerminated")
    println("  Terminating: $isTerminating")

    if (isTerminated) {
        val exitCode = handler.exitCode
        println("  Exit Code: $exitCode")
        println()
        if (exitCode == 0) {
            println("✓ Tests completed successfully")
        } else {
            println("✗ Tests completed with errors (exit code: $exitCode)")
        }
        println()
        println("Use 'inspect-test-results' example to view test results.")
    } else {
        println()
        println("Tests still running... Poll again to check completion.")
    }
}
