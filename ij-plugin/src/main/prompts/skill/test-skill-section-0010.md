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

```kotlin
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
