# Maven Dialog Investigation — Findings

## Root Cause

`MavenRunConfigurationType.runConfiguration()` uses `ApplicationManager.getApplication().invokeAndWait()` at line 188 of `MavenRunConfigurationType.java`. This creates a **modal pump loop** on the EDT, tracked by `JobProviderWithOwnerContext` (from `PlatformTaskSupport.kt:373`).

The dialog_killer detects `JobProviderWithOwnerContext` as a modal entity and kills it, which cancels the entire Maven execution before `SMTRunnerEventsListener.onTestingStarted` fires.

## Why Gradle Works

`GradleTestExecutionTest` uses `ProgramRunnerUtil.executeConfiguration()` which delegates to `executeConfigurationAsync()` — **no `invokeAndWait`, no modal context**. The dialog_killer has nothing to kill.

## The Wrong API

`MavenRunConfigurationType.runConfiguration()` is a convenience wrapper that:
1. Creates a `RunnerAndConfigurationSettings`
2. Creates an `ExecutionEnvironment`
3. Wraps `runner.execute(environment)` in `invokeAndWait()` ← THIS IS THE PROBLEM

## The Right API

Use the same pattern as Gradle — `ProgramRunnerUtil.executeConfiguration()`:

```kotlin
import com.intellij.execution.ProgramRunnerUtil
import com.intellij.execution.RunManager
import com.intellij.execution.executors.DefaultRunExecutor
import org.jetbrains.idea.maven.execution.MavenRunConfigurationType
import org.jetbrains.idea.maven.execution.MavenRunnerParameters

// Create Maven run configuration
val params = MavenRunnerParameters(true, project.basePath!!, "pom.xml",
    listOf("test", "-Dtest=MyTestClass"), emptyList())
val configSettings = MavenRunConfigurationType.createRunnerAndConfigurationSettings(
    null, null, params, project, "Maven test", false)

// Execute WITHOUT modal context
val runManager = RunManager.getInstance(project)
runManager.addConfiguration(configSettings)
ProgramRunnerUtil.executeConfiguration(configSettings, DefaultRunExecutor.getRunExecutorInstance())
```

This is build-system-agnostic in structure — same pattern works for Maven and Gradle.
The `SMTRunnerEventsListener` subscription pattern remains the same.

## Recommendation

1. Update `MavenTestExecutionTest` to use `ProgramRunnerUtil.executeConfiguration()` instead of `MavenRunConfigurationType.runConfiguration()`
2. Update skill resource patterns to use this approach
3. The `MavenRunConfigurationType.runConfiguration()` API should be documented as "creates modal context — not suitable for exec_code with dialog_killer"
