Test: Wait for Completion

This example polls the most recent test execution to check

```kotlin
import com.intellij.execution.ui.RunContentManager

val manager = RunContentManager.getInstance(project)
val allDescriptors = manager.allDescriptors

if (allDescriptors.isEmpty()) {
    println("No test executions found")
    return
}

// Get most recent descriptor
val descriptor = allDescriptors.lastOrNull()
if (descriptor == null) {
    println("No descriptor available")
    return
}

val handler = descriptor.processHandler

if (handler == null) {
    println("No process handler available")
    return
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
```

# See also

Related IDE operations:
- [Run Configuration](mcp-steroid://ide/run-configuration) - List and execute run configs

Related debugger operations:
- [Debug Run Configuration](mcp-steroid://debugger/debug-run-configuration) - Start debugging

Skill guides:
- [Test Runner Skill Guide](mcp-steroid://skill/test-skill) - Essential test knowledge
