# Test Execution Examples Overview

This directory contains runnable examples for IntelliJ test execution and result inspection APIs.

## Complete Test Execution Workflow

```
1. List run configurations
   ↓
2. Execute test configuration → RunContentDescriptor
   ↓
3. Get execution console → descriptor.getExecutionConsole()
   ↓
4. Cast to test console → console as? SMTRunnerConsoleView
   ↓
5. Get results viewer → consoleView.getResultsViewer()
   ↓
6. Access test tree → resultsForm.getTestsRootNode()
   ↓
7. Inspect results → testProxy.isPassed(), getChildren(), etc.
```

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
execute {
    waitForSmartMode()

    // Execute configuration
    val manager = RunManager.getInstance(project)
    val setting = manager.allSettings.first { it.name == "MyTests" }
    val executor = ExecutorRegistry.getExecutorById(DefaultRunExecutor.EXECUTOR_ID)!!

    var descriptor: RunContentDescriptor? = null
    withContext(Dispatchers.EDT) {
        descriptor = ProgramRunnerUtil.executeConfiguration(setting, executor)
    }

    println("Execution started: ${descriptor?.displayName}")
}

// Second call - poll for completion
execute {
    val manager = RunContentManager.getInstance(project)
    val descriptor = manager.allDescriptors.lastOrNull()
    val handler = descriptor?.processHandler

    if (handler?.isProcessTerminated == true) {
        println("Test execution completed with exit code: ${handler.exitCode}")
    } else {
        println("Tests still running...")
    }
}
```

### Pattern 2: Access Test Results

```kotlin
execute {
    val manager = RunContentManager.getInstance(project)
    val descriptor = manager.allDescriptors.lastOrNull()

    // Get test console
    val console = descriptor?.executionConsole as? SMTRunnerConsoleView
    if (console == null) {
        println("Not a test execution or results not available")
        return@execute
    }

    // Get test tree root
    val resultsForm = console.resultsViewer
    val rootProxy = resultsForm.testsRootNode

    // Inspect results
    println("Test Results:")
    println("  Passed: ${rootProxy.children.count { it.isPassed }}")
    println("  Failed: ${rootProxy.children.count { (it as? SMTestProxy)?.isFailed == true }}")
    println("  Total: ${rootProxy.children.size}")

    // Print failures
    rootProxy.children.forEach { test ->
        val smTest = test as? SMTestProxy
        if (smTest?.isFailed == true) {
            println("\nFailed: ${smTest.name}")
            println("  Error: ${smTest.errorMessage}")
            println("  Stack: ${smTest.stacktrace?.take(200)}...")
        }
    }
}
```

### Pattern 3: Navigate Test Tree Recursively

```kotlin
execute {
    fun printTestTree(proxy: AbstractTestProxy, indent: String = "") {
        val status = when {
            proxy.isPassed -> "✓"
            (proxy as? SMTestProxy)?.isFailed == true -> "✗"
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
}
```

## Threading Considerations

- Execute run configurations on EDT: `withContext(Dispatchers.EDT) { ... }`
- Access PSI/VFS in read actions: `readAction { ... }`
- Poll completion status from background thread (no EDT blocking)
- Test tree navigation can be done on any thread after tests complete

## Stateful Execution

Remember that `execute {}` blocks maintain IDE state between calls:

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

### Related Example Guides
- [Debugger Examples](mcp-steroid://debugger/overview) - Debugging workflows
- [IDE Examples](mcp-steroid://ide/overview) - IDE power operations
- [LSP Examples](mcp-steroid://lsp/overview) - Code navigation
- [Open Project Examples](mcp-steroid://open-project/overview) - Project opening
