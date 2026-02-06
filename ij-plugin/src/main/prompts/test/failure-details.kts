import com.intellij.execution.ui.RunContentManager
import com.intellij.execution.testframework.sm.runner.ui.SMTRunnerConsoleView
import com.intellij.execution.testframework.sm.runner.SMTestProxy

val manager = RunContentManager.getInstance(project)
val descriptor = manager.allDescriptors.lastOrNull()

if (descriptor == null) {
    println("No test execution found")
    return
}

val console = descriptor.executionConsole as? SMTRunnerConsoleView
if (console == null) {
    println("Not a test execution")
    return
}

val resultsForm = console.resultsViewer
val rootProxy = resultsForm.testsRootNode

// Find all failed tests
val failedTests = rootProxy.allTests
    .filterIsInstance<SMTestProxy>()
    .filter { it.isDefect }

if (failedTests.isEmpty()) {
    println("✓ No test failures found")
    println()
    println("All tests passed!")
    return
}

// Print failure details
println("Failed Tests: ${failedTests.size}")
println("═══════════════════════════════════════")
println()

failedTests.forEachIndexed { index, test ->
    println("${index + 1}. ${test.name}")
    println("   ─────────────────────────────────────")

    // Duration
    val duration = test.duration
    println("   Duration: ${duration}ms")

    // Error message
    val errorMessage = test.errorMessage
    if (errorMessage != null && errorMessage.isNotBlank()) {
        println()
        println("   Error Message:")
        errorMessage.lines().forEach { line ->
            println("     $line")
        }
    }

    // Stack trace
    val stacktrace = test.stacktrace
    if (stacktrace != null && stacktrace.isNotBlank()) {
        println()
        println("   Stack Trace:")
        stacktrace.lines().forEach { line ->
            println("     $line")
        }
    }

    // Location
    val locationUrl = test.locationUrl
    if (locationUrl != null && locationUrl.isNotBlank()) {
        println()
        println("   Location: $locationUrl")
    }

    // Test magnitude (severity)
    val magnitude = test.magnitude
    val severityStr = when (magnitude) {
        0 -> "Passed"
        1 -> "Skipped/Ignored"
        2 -> "Not Run"
        4 -> "Terminated"
        5 -> "Ignored"
        6 -> "Error"
        8 -> "Failed"
        else -> "Unknown ($magnitude)"
    }
    println("   Severity: $severityStr")

    println()
}

println("═══════════════════════════════════════")
println("✗ Total Failures: ${failedTests.size}")
