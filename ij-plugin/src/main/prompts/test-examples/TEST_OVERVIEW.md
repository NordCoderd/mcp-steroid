# Test Execution Examples Overview

This directory contains runnable examples for IntelliJ test execution and result inspection APIs.

## Complete Test Execution Workflow

```
1. List run configurations
   ↓
2. Execute test configuration (ProgramRunnerUtil)
   ↓
3. Find RunContentDescriptor via RunContentManager
   ↓
4. Get execution console → descriptor.getExecutionConsole()
   ↓
5. Cast to test console → console as? SMTRunnerConsoleView
   ↓
6. Get results viewer → consoleView.getResultsViewer()
   ↓
7. Access test tree → resultsForm.getTestsRootNode()
   ↓
8. Inspect results → testProxy.isPassed(), isDefect(), getChildren(), etc.
```

## Demo Sanity Check (This Repo)

Use the demo test `DemoTestByJonnyzzz` in `com.jonnyzzz.mcpSteroid.ocr` to verify the full flow:

1. Create or select a run configuration for `DemoTestByJonnyzzz`
   - Add VM option: `-Dmcp.demo.by.jonnyzzz=true` (the test is skipped otherwise)
2. Use [List Run Configurations](mcp-steroid://test/list-run-configurations) to find its name
3. Start it with [Run Tests](mcp-steroid://test/run-tests) (Run or Debug executor)
4. Poll with [Wait for Completion](mcp-steroid://test/wait-for-completion)
5. Inspect results via [Inspect Test Results](mcp-steroid://test/inspect-test-results)

Or run [Demo Debug Test](mcp-steroid://test/demo-debug-test) for a one-call end-to-end debug flow.

## Available Examples

### Basic Test Execution

- **`list-run-configurations.md`** - List all run configurations in the project
- **`run-tests.md`** - Execute a test run configuration
- **`wait-for-completion.md`** - Wait for test execution to complete
- **`inspect-test-results.md`** - Access and inspect test results

### Test Result Navigation

- **`test-tree-navigation.md`** - Navigate test tree structure
- **`test-failure-details.md`** - Access failure messages and stack traces
- **`test-statistics.md`** - Get test counts (passed/failed/ignored)

### Advanced Patterns

- **`demo-debug-test.md`** - End-to-end debug run for the demo test
- **`listen-execution-events.md`** - Use ExecutionListener for execution lifecycle
- **`access-test-output.md`** - Read test console output
- **`find-recent-test-run.md`** - Access most recent test execution

## Key API Classes

### Run Configuration Management
- `RunManager` - Access run configurations
- `RunnerAndConfigurationSettings` - Configuration settings
- `ProgramRunnerUtil` - Execute configurations
- `ExecutorRegistry` - Get executors (Run, Debug, etc.)

### Test Execution Results
- `RunContentDescriptor` - Handle to execution results
- `SMTRunnerConsoleView` - Test console view
- `SMTestRunnerResultsForm` - Test results form with tree
- `SMRootTestProxy` / `SMTestProxy` - Test tree nodes
- `AbstractTestProxy` - Base class for test nodes

### Process Management
- `ProcessHandler` - Control and monitor process
- `ExecutionListener` - Listen to execution lifecycle events
- `RunContentManager` - Access all running/completed processes

## Common Patterns

### Pattern 1: Execute and Wait

```kotlin

// Execute configuration
val manager = RunManager.getInstance(project)
val setting = manager.allSettings.first { it.name == "MyTests" }
val executor = ExecutorRegistry.getExecutorById(DefaultRunExecutor.EXECUTOR_ID)!!

withContext(Dispatchers.EDT) {
    ProgramRunnerUtil.executeConfiguration(setting, executor)
}

println("Execution started: ${setting.name}")

// Second call - poll for completion
val manager = RunContentManager.getInstance(project)
val descriptor = manager.allDescriptors.lastOrNull { it.displayName == "MyTests" }
val handler = descriptor?.processHandler

if (handler?.isProcessTerminated == true) {
    println("Test execution completed with exit code: ${handler.exitCode}")
} else {
    println("Tests still running...")
}
```

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

### Pattern 3: Navigate Test Tree Recursively

```kotlin
fun printTestTree(proxy: AbstractTestProxy, indent: String = "") {
    val status = when {
        proxy.isPassed -> "✓"
        proxy.isDefect -> "✗"
        proxy.isIgnored -> "○"
        proxy.isInProgress -> "→"
        else -> "?"
    }

    println("$indent$status ${proxy.name} (${proxy.duration}ms)")

    proxy.children.forEach { child ->
        printTestTree(child, "$indent  ")
    }
}

val manager = RunContentManager.getInstance(project)
val descriptor = manager.allDescriptors.lastOrNull()
val console = descriptor?.executionConsole as? SMTRunnerConsoleView
val rootProxy = console?.resultsViewer?.testsRootNode

if (rootProxy != null) {
    printTestTree(rootProxy)
}
```

## Threading Considerations

- Execute run configurations on EDT: `withContext(Dispatchers.EDT) { ... }`
- Access PSI/VFS in read actions: `readAction { ... }`
- Poll completion status from background thread (no EDT blocking)
- Test tree navigation can be done on any thread after tests complete

## Stateful Execution

Remember that each `steroid_execute_code` call runs in the same IDE process; state persists between calls:

1. **Call 1**: Start test execution
2. **Call 2+**: Poll for completion (quick, non-blocking checks)
3. **Final call**: Inspect results after completion

This pattern avoids timeout issues and provides better feedback to the agent.

---

## Related Resources

### Skill Guides
- [IntelliJ API Power User Guide](mcp-steroid://skill/intellij-api-poweruser-guide) - Core API reference and patterns
- [Debugger Skill Guide](mcp-steroid://skill/debugger-guide) - Debug workflows and session management
- [Test Runner Skill Guide](mcp-steroid://skill/test-runner-guide) - Essential test execution knowledge

### Test Execution Examples
- [Test Overview](mcp-steroid://test/overview) - This document
- [List Run Configurations](mcp-steroid://test/list-run-configurations) - Discover available tests
- [Run Tests](mcp-steroid://test/run-tests) - Execute test configurations
- [Inspect Test Results](mcp-steroid://test/inspect-test-results) - Access results
- [Test Tree Navigation](mcp-steroid://test/test-tree-navigation) - Navigate test hierarchy
- [Wait for Completion](mcp-steroid://test/wait-for-completion) - Poll test status
- [Demo Debug Test](mcp-steroid://test/demo-debug-test) - End-to-end debug flow for demo test

### Related Example Guides
- [Debugger Examples](mcp-steroid://debugger/overview) - Debugging workflows
- [IDE Examples](mcp-steroid://ide/overview) - IDE power operations
- [LSP Examples](mcp-steroid://lsp/overview) - Code navigation
- [Open Project Examples](mcp-steroid://open-project/overview) - Project opening
