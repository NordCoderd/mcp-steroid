
### Pattern 2: Access Test Results

```kotlin
val manager = RunContentManager.getInstance(project)
val descriptor = manager.allDescriptors.lastOrNull()

// Get test console
val console = descriptor?.executionConsole as? SMTRunnerConsoleView
if (console == null) {
    println("Not a test execution or results not available")
    return
}

// Get test tree root
val resultsForm = console.resultsViewer
val rootProxy = resultsForm.testsRootNode

// Inspect results
val allTests = rootProxy.allTests
val passed = allTests.count { it.isPassed }
val failed = allTests.count { it.isDefect }
val ignored = allTests.count { it.isIgnored }

println("Test Results:")
println("  Passed: $passed")
println("  Failed: $failed")
println("  Ignored: $ignored")
println("  Total: ${allTests.size}")

// Print failures
rootProxy.children.forEach { test ->
    val smTest = test as? SMTestProxy
    if (smTest?.isDefect == true) {
        println("\nFailed: ${smTest.name}")
        println("  Error: ${smTest.errorMessage}")
        println("  Stack: ${smTest.stacktrace?.take(200)}...")
    }
}
```
