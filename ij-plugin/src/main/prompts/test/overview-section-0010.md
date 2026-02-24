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
