IntelliJ Test Runner Skill Guide

Use IntelliJ test execution APIs via steroid_execute_code to run tests, inspect test results, check test status, and access test output.

---
name: mcp-steroid-test-runner
description: Use IntelliJ test execution APIs via steroid_execute_code to run tests, inspect test results, check test status, and access test output.
---

# IntelliJ Test Runner Skill

Use IntelliJ test execution APIs from `steroid_execute_code` to run tests and inspect results.

## Quickstart

1) Load `mcp-steroid://test/overview` and pick the examples you need.
2) List available run configurations (example: `mcp-steroid://test/list-run-configurations`).
3) Execute a test configuration (example: `mcp-steroid://test/run-tests`).
4) Poll for completion and access results (example: `mcp-steroid://test/inspect-test-results`).
5) Navigate test tree and check individual test status (example: `mcp-steroid://test/tree-navigation`).

## Stateful exec_code workflow

`exec_code` is stateful. Split test execution into multiple short calls:

- Call #1: list run configurations
- Call #2: execute test configuration (ProgramRunnerUtil)
- Call #3: locate RunContentDescriptor via RunContentManager
- Call #3+: poll ProcessHandler.isProcessTerminated() until tests complete
- Call #4: access test results from SMTRunnerConsoleView
- Call #5: navigate test tree and inspect failures

Avoid long waits or sleeps inside a single call; prefer multiple short polls.

## Run a Specific JUnit Test Class (correct API)

```text
import com.intellij.execution.junit.JUnitConfiguration
import com.intellij.execution.junit.JUnitConfigurationType
import com.intellij.execution.RunManager
import com.intellij.execution.ProgramRunnerUtil
import com.intellij.execution.executors.DefaultRunExecutor

val factory = JUnitConfigurationType.getInstance().configurationFactories.first()
val config = factory.createConfiguration("Run test", project) as JUnitConfiguration
val data = config.persistentData               // typed as JUnitConfiguration.Data
data.TEST_CLASS = "com.example.MyValidatorTest"
data.TEST_OBJECT = JUnitConfiguration.TEST_CLASS  // ← constant, NOT a string literal "class"
config.setWorkingDirectory(project.basePath!!)
val settings = RunManager.getInstance(project).createConfiguration(config, factory)
RunManager.getInstance(project).addConfiguration(settings)
ProgramRunnerUtil.executeConfiguration(settings, DefaultRunExecutor.getRunExecutorInstance())
println("Test run started")
```

⚠️ **Common mistake**: `data.TEST_OBJECT = "class"` or `data.TEST_CLASS` as property on a supertype → compile error
`"unresolved reference 'TEST_CLASS'"`. Always use `JUnitConfiguration.TEST_CLASS` (the static constant).

**Alternative — run via Maven wrapper (simpler, no IDE runner needed):**
```kotlin
// ⚠️ Always use ./mvnw (Maven wrapper) not 'mvn' — system mvn is not installed in arena
val process = ProcessBuilder("./mvnw", "test", "-Dtest=MyValidatorTest", "-q")
    .directory(java.io.File(project.basePath!!))
    .redirectErrorStream(true).start()
println(process.inputStream.bufferedReader().readText())
process.waitFor()
```

## Key APIs to use

**Run Configuration Management**
- `RunManager.getInstance(project)` - access all run configurations
- `RunManager.allSettings` - list of RunnerAndConfigurationSettings
- `RunManager.selectedConfiguration` - currently selected config

**Execution**
- `ProgramRunnerUtil.executeConfiguration(settings, executor)` - execute config
- `ExecutorRegistry.getExecutorById(DefaultRunExecutor.EXECUTOR_ID)` - get Run executor
- `ExecutorRegistry.getExecutorById(DefaultDebugExecutor.EXECUTOR_ID)` - get Debug executor

**Accessing Results**
- `RunContentManager.getInstance(project).allDescriptors` - all run content
- `RunContentDescriptor.getExecutionConsole()` - get console (may be SMTRunnerConsoleView)
- `SMTRunnerConsoleView.getResultsViewer()` - get SMTestRunnerResultsForm
- `SMTestRunnerResultsForm.getTestsRootNode()` - get test tree root (SMRootTestProxy)

**Test Result Inspection**
- `SMTestProxy.isPassed()`, `isDefect()`, `isIgnored()`, `isInProgress()`
- `SMTestProxy.getChildren()` - navigate test tree
- `SMTestProxy.getErrorMessage()`, `getStacktrace()` - failure details
- `SMTestProxy.getDuration()` - execution time
- `SMTestProxy.getAllTests()` - flatten test tree

**Process Monitoring**
- `RunContentDescriptor.getProcessHandler()` - get ProcessHandler
- `ProcessHandler.isProcessTerminated()` - check if execution finished
- `ProcessHandler.exitCode` - get exit code (if available)

## Common pitfalls

- Test results are only available after execution starts (descriptor.executionConsole may be null initially).
- ExecutionConsole must be cast to SMTRunnerConsoleView for test-specific features.
- Test tree is populated asynchronously as tests run.
- Always check `isProcessTerminated()` before accessing final results.
- Execute configurations on EDT (use `withContext(Dispatchers.EDT)`).
- Wait for smart mode before accessing run configurations.

## Related Resources

### Skill Guides
- [IntelliJ API Power User Guide](mcp-steroid://skill/skill) - Essential IntelliJ API patterns
- [Debugger Skill Guide](mcp-steroid://skill/debugger-skill) - Debug session management
- [Test Runner Skill Guide](mcp-steroid://skill/test-skill) - This guide

### Test Execution Resources
- [Test Overview](mcp-steroid://test/overview) - Complete test execution workflow and patterns
- [List Run Configurations](mcp-steroid://test/list-run-configurations) - Discover available test configs
- [Run Tests](mcp-steroid://test/run-tests) - Execute test configurations
- [Inspect Test Results](mcp-steroid://test/inspect-test-results) - Access and analyze test results
- [Test Tree Navigation](mcp-steroid://test/tree-navigation) - Navigate test hierarchy
- [Wait for Completion](mcp-steroid://test/wait-for-completion) - Poll for test execution status

### Related Example Guides
- [Debugger Examples](mcp-steroid://debugger/overview) - Debugging and session management
- [IDE Examples](mcp-steroid://ide/overview) - IDE power operations
- [Open Project Examples](mcp-steroid://open-project/overview) - Project opening workflows
