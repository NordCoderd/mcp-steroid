Test Execution Examples Overview

Overview of IntelliJ test execution and result inspection examples.

# Test Execution Examples Overview

This directory contains runnable examples for IntelliJ test execution and result inspection APIs.

## Preferred Workflow — Run Test at Caret (IDE-agnostic)

```
1. Open test file in editor
   ↓
2. Position caret on test method or class
   ↓
3. Fire RunClass / DebugClass (IntelliJ) or RiderUnitTestRunContextAction (Rider)
   — Action title shows test name: "Run 'myTestMethod'" / "Debug 'myTestMethod'"
   — Default shortcut: Ctrl+Shift+F10 (run) — shown in gutter icon tooltip
   ↓
4. Poll RunContentManager → ProcessHandler.isProcessTerminated()
   ↓
5. Inspect results via SMTRunnerConsoleView
```

See `mcp-steroid://test/run-test-at-caret` for the full runnable example.

## Alternative Workflow — Run by Named Configuration

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

- **`run-test-at-caret.md`** - Run/debug at caret position (IDE-agnostic, preferred)
- **`list-run-configurations.md`** - List all run configurations in the project
- **`run-tests.md`** - Execute a named test run configuration
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
import com.intellij.execution.RunManager
import com.intellij.execution.ExecutorRegistry
import com.intellij.execution.ProgramRunnerUtil
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.execution.ui.RunContentManager

// Execute configuration
val manager = RunManager.getInstance(project)
val setting = manager.allSettings.first { it.name == "MyTests" }
val executor = ExecutorRegistry.getInstance().getExecutorById(DefaultRunExecutor.EXECUTOR_ID)!!

withContext(Dispatchers.EDT) {
    ProgramRunnerUtil.executeConfiguration(setting, executor)
}

println("Execution started: ${setting.name}")

// Second call - poll for completion
val runContentManager = RunContentManager.getInstance(project)
val descriptor = runContentManager.allDescriptors.lastOrNull { it.displayName == "MyTests" }
val handler = descriptor?.processHandler

if (handler?.isProcessTerminated == true) {
    println("Test execution completed with exit code: ${handler.exitCode}")
} else {
    println("Tests still running...")
}
```

### Pattern 2: Access Test Results

```kotlin
import com.intellij.execution.ui.RunContentManager
import com.intellij.execution.testframework.sm.runner.ui.SMTRunnerConsoleView

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
    if (test.isDefect) {
        println("\nFailed: ${test.name}")
        println("  Error: ${test.errorMessage}")
        println("  Stack: ${test.stacktrace?.take(200)}...")
    }
}
```

### Pattern 3: Navigate Test Tree Recursively

```kotlin
import com.intellij.execution.testframework.AbstractTestProxy
import com.intellij.execution.ui.RunContentManager
import com.intellij.execution.testframework.sm.runner.ui.SMTRunnerConsoleView

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
- [IntelliJ API Power User Guide](mcp-steroid://prompt/skill) - Core API reference and patterns
- [Debugger Skill Guide](mcp-steroid://prompt/debugger-skill) - Debug workflows and session management
- [Test Runner Skill Guide](mcp-steroid://prompt/test-skill) - Essential test execution knowledge

### Test Execution Examples
- [Test Overview](mcp-steroid://test/overview) - This document
- [Run Test at Caret](mcp-steroid://test/run-test-at-caret) - IDE-agnostic caret context action
- [List Run Configurations](mcp-steroid://test/list-run-configurations) - Discover available tests
- [Run Tests](mcp-steroid://test/run-tests) - Execute named test configurations
- [Inspect Test Results](mcp-steroid://test/inspect-test-results) - Access results
- [Test Tree Navigation](mcp-steroid://test/tree-navigation) - Navigate test hierarchy
- [Wait for Completion](mcp-steroid://test/wait-for-completion) - Poll test status
- [Demo Debug Test](mcp-steroid://test/demo-debug-test) - End-to-end debug flow for demo test

### Related Example Guides
- [Debugger Examples](mcp-steroid://debugger/overview) - Debugging workflows
- [IDE Examples](mcp-steroid://ide/overview) - IDE power operations
- [LSP Examples](mcp-steroid://lsp/overview) - Code navigation
- [Open Project Examples](mcp-steroid://open-project/overview) - Project opening
