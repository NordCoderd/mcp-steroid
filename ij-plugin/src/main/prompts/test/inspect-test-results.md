Test: Inspect Test Results

This example accesses test results from the most recent test execution

```kotlin
import com.intellij.execution.ui.RunContentManager
import com.intellij.execution.testframework.sm.runner.ui.SMTRunnerConsoleView
import com.intellij.execution.testframework.sm.runner.SMTestProxy

val manager = RunContentManager.getInstance(project)
val descriptor = manager.allDescriptors.lastOrNull()

if (descriptor == null) {
    println("No test execution found")
    println("Run a test configuration first using 'run-tests' example.")
    return
}

// Check if process is still running
val handler = descriptor.processHandler
if (handler?.isProcessTerminated == false) {
    println("Tests are still running. Wait for completion first.")
    println("Use 'wait-for-completion' example to poll for completion.")
    return
}

// Get execution console
val console = descriptor.executionConsole

// Check if it's a test console
if (console !is SMTRunnerConsoleView) {
    println("Not a test execution or results not available")
    println("Execution console type: ${console?.javaClass?.simpleName}")
    return
}

// Get test results
val resultsForm = console.resultsViewer
val rootProxy = resultsForm.testsRootNode

// Collect statistics
val allTests = rootProxy.allTests
val passed = allTests.count { it.isPassed }
val failed = allTests.count { it.isDefect }
val ignored = allTests.count { it.isIgnored }
val total = allTests.size

val totalDuration = rootProxy.duration

// Print summary
println("═══════════════════════════════════════")
println("Test Results: ${descriptor.displayName}")
println("═══════════════════════════════════════")
println()
println("  ✓ Passed:  $passed")
println("  ✗ Failed:  $failed")
println("  ○ Ignored: $ignored")
println("  ─────────────")
println("  Total:     $total")
println()
println("  Duration: ${totalDuration}ms")
println()

// Print failed tests
if (failed > 0) {
    println("Failed Tests:")
    println("─────────────")
    allTests.forEach { test ->
        val smTest = test as SMTestProxy
        if (smTest.isDefect) {
            println()
            println("✗ ${smTest.name}")

            val errorMsg = smTest.errorMessage
            if (errorMsg != null && errorMsg.isNotBlank()) {
                println("  Error: $errorMsg")
            }

            val stacktrace = smTest.stacktrace
            if (stacktrace != null && stacktrace.isNotBlank()) {
                // Print first few lines of stack trace
                val lines = stacktrace.lines().take(5)
                lines.forEach { line ->
                    println("    $line")
                }
                if (stacktrace.lines().size > 5) {
                    println("    ... (${stacktrace.lines().size - 5} more lines)")
                }
            }

            val duration = smTest.duration
            println("  Duration: ${duration}ms")

            val locationUrl = smTest.locationUrl
            if (locationUrl != null) {
                println("  Location: $locationUrl")
            }
        }
    }
}

println()
println("═══════════════════════════════════════")

if (failed == 0) {
    println("✓ All tests passed!")
} else {
    println("✗ $failed test(s) failed")
}
```
