### Completion Rule: Separate Code Correctness from Environment Availability

Do not collapse everything into one "test failed" bucket. Track two independent axes:

1. **Code correctness** - inspections/build checks on changed files
2. **Environment availability** - whether runtime infra (Docker/Testcontainers, etc.) is available

A fix is complete when code is correct and either:
- target tests pass, or
- target and baseline tests fail with the same infrastructure signature

```kotlin
import java.io.File
import java.util.concurrent.TimeUnit

data class TestOutcome(
    val name: String,
    val exitCode: Int?,
    val timedOut: Boolean,
    val lines: List<String>,
) {
    val passed: Boolean get() = !timedOut && exitCode == 0
    val dockerUnavailable: Boolean
        get() = lines.any { "Could not find a valid Docker environment" in it }
}

fun runMavenTest(testClass: String, timeoutSec: Long = 180): TestOutcome {
    val process = ProcessBuilder("./mvnw", "test", "-Dtest=$testClass", "-q")
        .directory(File(project.basePath!!))
        .redirectErrorStream(true)
        .start()
    val lines = process.inputStream.bufferedReader().readLines()
    val finished = process.waitFor(timeoutSec, TimeUnit.SECONDS)
    return if (finished) {
        TestOutcome(testClass, process.exitValue(), timedOut = false, lines = lines)
    } else {
        process.destroyForcibly()
        TestOutcome(testClass, exitCode = null, timedOut = true, lines = lines)
    }
}

val changedPaths = listOf(
    "src/main/java/com/example/api/MyController.java",
    "src/main/java/com/example/service/MyService.java",
)
val inspectionFailures = mutableListOf<String>()
for (path in changedPaths) {
    val vf = findProjectFile(path) ?: continue
    val problems = runInspectionsDirectly(vf)
    if (problems.isNotEmpty()) inspectionFailures += path
}
val codeReady = inspectionFailures.isEmpty()

val target = runMavenTest("com.example.api.MyFeatureIntegrationTest")
val baseline = runMavenTest("com.example.api.ExistingIntegrationBaselineTest")
val infraBlocked = target.dockerUnavailable && baseline.dockerUnavailable

val complete = codeReady && (target.passed || infraBlocked)
println("CODE_READY=$codeReady TARGET_PASSED=${target.passed} INFRA_BLOCKED=$infraBlocked")
println("VERIFICATION_DECISION=${if (complete) "COMPLETE" else "INCOMPLETE"}")
```

#### Maven projects — `MavenRunConfigurationType.runConfiguration()`

> **⚠️ CRITICAL — After editing pom.xml: do NOT use this IDE runner immediately.**
> When `pom.xml` is modified, IntelliJ triggers a Maven project re-import that shows a
> modal dialog. This dialog **blocks the latch for up to 600 seconds** — wasting an entire
> agent turn. Multiply by 3 agents hitting the same issue = 1800 seconds lost.
>
> **Rule**: After editing `pom.xml`, use `ProcessBuilder("./mvnw", ...)` (see the
> "Run Unit Tests via Maven Wrapper" section below) as your PRIMARY test runner.
> The Maven IDE runner is reliable only when no build file was modified in the current session.
>
> If you do use the Maven IDE runner (e.g. for an unmodified project), always pass
> `dialog_killer: true` on the `steroid_execute_code` call to auto-dismiss any dialogs.
> If the latch still times out after 2 minutes, fall back to `ProcessBuilder("./mvnw", ...)`
> immediately — do not wait the full 10 minutes.

```kotlin
import org.jetbrains.idea.maven.execution.MavenRunConfigurationType
import org.jetbrains.idea.maven.execution.MavenRunnerParameters
import org.jetbrains.idea.maven.execution.MavenRunnerSettings
import com.intellij.execution.testframework.sm.runner.SMTRunnerEventsListener
import com.intellij.execution.testframework.sm.runner.SMTestProxy
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout
import kotlin.time.Duration.Companion.minutes

val result = CompletableDeferred<Boolean>()

// Subscribe BEFORE launching so we don't miss the event
val connection = project.messageBus.connect()
connection.subscribe(SMTRunnerEventsListener.TEST_STATUS, object : SMTRunnerEventsListener {
    override fun onTestingFinished(testsRoot: SMTestProxy.SMRootTestProxy) {
        val passed = testsRoot.isPassed()
        val failed = testsRoot.getAllTests().count { it.isDefect }
        println("Tests finished — passed=$passed failures=$failed")
        connection.disconnect()
        result.complete(passed)
    }
    override fun onTestingStarted(testsRoot: SMTestProxy.SMRootTestProxy) {}
    override fun onTestFailed(test: SMTestProxy) { println("FAILED: ${test.name}") }
    // ⚠️ ALL abstract methods must be implemented — SMTRunnerEventsListener is NOT an adapter class
    // (SMTRunnerEventsAdapter was removed in IntelliJ 2025.x; missing stubs → compilation failure)
    override fun onTestsCountInSuite(count: Int) {}
    override fun onTestStarted(test: SMTestProxy) {}
    override fun onTestFinished(test: SMTestProxy) {}
    override fun onTestIgnored(test: SMTestProxy) {}
    override fun onSuiteFinished(suite: SMTestProxy) {}
    override fun onSuiteStarted(suite: SMTestProxy) {}
    override fun onCustomProgressTestsCategory(categoryName: String?, count: Int) {}
    override fun onCustomProgressTestStarted() {}
    override fun onCustomProgressTestFailed() {}
    override fun onCustomProgressTestFinished() {}
    override fun onSuiteTreeNodeAdded(testProxy: SMTestProxy) {}
    override fun onSuiteTreeStarted(suite: SMTestProxy) {}
})

// Launch via Maven IDE runner (runs through Maven lifecycle, resolves deps)
MavenRunConfigurationType.runConfiguration(
    project,
    MavenRunnerParameters(
        /* isPomExecution= */ true,
        /* workingDirPath= */ project.basePath!!,
        /* pomFileName= */ "pom.xml",
        /* goals= */ listOf("test", "-Dtest=com.example.MyTest", "-Dspotless.check.skip=true"),
        /* profiles= */ emptyList()
    ),
    /* settings (MavenGeneralSettings) = */ null,
    /* runnerSettings (MavenRunnerSettings) = */ null,
) { /* ProgramRunner.Callback — completion handled by SMTRunnerEventsListener above */ }

val passed = withTimeout(5.minutes) { result.await() }
println("Result: passed=$passed")
```

> **⚠️ Docker / CI environments — use `dialog_killer: true`**: When running `MavenRunConfigurationType.runConfiguration()` in a Docker or CI container, Maven project-reimport dialogs can block the run silently for the full latch timeout (5 minutes wasted). Pass `dialog_killer: true` as the `steroid_execute_code` parameter to auto-dismiss these modals. If the latch still times out after 2-3 minutes despite `dialog_killer: true`, **stop waiting and fall back to `ProcessBuilder("./mvnw", ...)` immediately** — do not wait the full 5 minutes.

#### Gradle projects — `GradleRunConfiguration.setRunAsTest(true)`

```kotlin
import org.jetbrains.plugins.gradle.service.execution.GradleExternalTaskConfigurationType
import org.jetbrains.plugins.gradle.service.execution.GradleRunConfiguration
import com.intellij.execution.RunManager
import com.intellij.execution.runners.ProgramRunnerUtil
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.execution.testframework.sm.runner.SMTRunnerEventsListener
import com.intellij.execution.testframework.sm.runner.SMTestProxy
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout
import kotlin.time.Duration.Companion.minutes

val result = CompletableDeferred<Boolean>()

val connection = project.messageBus.connect()
connection.subscribe(SMTRunnerEventsListener.TEST_STATUS, object : SMTRunnerEventsListener {
    override fun onTestingFinished(testsRoot: SMTestProxy.SMRootTestProxy) {
        val passed = testsRoot.isPassed()
        println("Tests finished — passed=$passed")
        connection.disconnect()
        result.complete(passed)
    }
    override fun onTestingStarted(testsRoot: SMTestProxy.SMRootTestProxy) {}
    override fun onTestFailed(test: SMTestProxy) { println("FAILED: ${test.name}") }
    // ⚠️ ALL abstract methods must be implemented — SMTRunnerEventsListener is NOT an adapter class
    override fun onTestsCountInSuite(count: Int) {}
    override fun onTestStarted(test: SMTestProxy) {}
    override fun onTestFinished(test: SMTestProxy) {}
    override fun onTestIgnored(test: SMTestProxy) {}
    override fun onSuiteFinished(suite: SMTestProxy) {}
    override fun onSuiteStarted(suite: SMTestProxy) {}
    override fun onCustomProgressTestsCategory(categoryName: String?, count: Int) {}
    override fun onCustomProgressTestStarted() {}
    override fun onCustomProgressTestFailed() {}
    override fun onCustomProgressTestFinished() {}
    override fun onSuiteTreeNodeAdded(testProxy: SMTestProxy) {}
    override fun onSuiteTreeStarted(suite: SMTestProxy) {}
})

val configurationType = GradleExternalTaskConfigurationType.getInstance()
val factory = configurationType.configurationFactories[0]
val config = GradleRunConfiguration(project, factory, "Run MyTest")
config.settings.externalProjectPath = project.basePath!!
config.settings.taskNames = listOf(":test")
config.settings.scriptParameters = "--tests \"com.example.MyTest\""
config.setRunAsTest(true)  // CRITICAL: enables test console / SMTestProxy wiring

val runManager = RunManager.getInstance(project)
val settings = runManager.createConfiguration(config, factory)
runManager.addConfiguration(settings)
ProgramRunnerUtil.executeConfiguration(settings, DefaultRunExecutor.getRunExecutorInstance())

val passed = withTimeout(5.minutes) { result.await() }
println("Result: passed=$passed")
```

#### Auto-detect runner via ConfigurationContext (simplest, works for any build system)

This is exactly what the green ▶ gutter button does:

```kotlin
import com.intellij.execution.actions.ConfigurationContext
import com.intellij.execution.ProgramRunnerUtil
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.openapi.actionSystem.SimpleDataContext
import com.intellij.openapi.actionSystem.CommonDataKeys

val psiClass = readAction {
    JavaPsiFacade.getInstance(project).findClass("com.example.MyTest", projectScope())
} ?: error("Class not found")

val settings = readAction {
    ConfigurationContext.getFromContext(
        SimpleDataContext.builder()
            .add(CommonDataKeys.PROJECT, project)
            .add(CommonDataKeys.PSI_ELEMENT, psiClass)
            .build()
    ).configuration
} ?: error("No run configuration produced for this class")

// Auto-selects Maven/Gradle/JUnit runner based on project structure
ProgramRunnerUtil.executeConfiguration(settings, DefaultRunExecutor.getRunExecutorInstance())
println("Test started — check IDE Test Results window")
```

---
