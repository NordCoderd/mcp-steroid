Execute Code: Maven Patterns

Running Maven builds and tests via IntelliJ Maven APIs instead of ProcessBuilder.

# Execute Code: Maven Patterns

## Primary: MavenRunner + MavenRunnerParameters

Use `MavenRunner` for all Maven goal execution — it runs inside the IDE JVM, reuses IntelliJ's Maven installation, and avoids spawning a separate process:

```kotlin[IU]
import org.jetbrains.idea.maven.execution.MavenRunner
import org.jetbrains.idea.maven.execution.MavenRunnerParameters
import org.jetbrains.idea.maven.execution.MavenRunnerSettings
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout
import kotlin.time.Duration.Companion.minutes

val done = CompletableDeferred<Boolean>()
val params = MavenRunnerParameters(
    /* isPomExecution    = */ true,
    /* workingDirPath   = */ project.basePath!!,
    /* pomFileName      = */ null,              // null → use default pom.xml
    /* goals            = */ listOf("package"),
    /* enabledProfiles  = */ emptyList()
)
val runner = MavenRunner.getInstance(project)
val settings: MavenRunnerSettings = runner.settings.clone()
settings.mavenProperties["spotless.check.skip"] = "true"
runner.run(params, settings) { done.complete(true) }
// Note: callback fires only on exit code 0. If Maven fails, the deferred never completes.
// For pass/fail semantics use MavenRunConfigurationType + SMTRunnerEventsListener (see execute-code-testing.md).
val ok = withTimeout(5.minutes) { done.await() }
println("Maven goal completed: $ok")
```

**Import paths:**
```kotlin[IU]
import org.jetbrains.idea.maven.execution.MavenRunner             // project service
import org.jetbrains.idea.maven.execution.MavenRunnerParameters   // goal + working dir
import org.jetbrains.idea.maven.execution.MavenRunnerSettings     // properties, skip flags
import org.jetbrains.idea.maven.execution.MavenRunConfigurationType  // lower-level static entry
import org.jetbrains.idea.maven.project.MavenProjectsManager      // sync/reload
import org.jetbrains.idea.maven.buildtool.MavenSyncSpec            // full/incremental sync spec
```

---

## Structured Pass/Fail for Test Runs — SMTRunnerEventsListener

For Maven test execution with explicit pass/fail result, use `MavenRunConfigurationType.runConfiguration` + `SMTRunnerEventsListener`. See **`mcp-steroid://skill/execute-code-testing`** for the complete pattern. Summary:

```kotlin[IU]
import org.jetbrains.idea.maven.execution.MavenRunConfigurationType
import org.jetbrains.idea.maven.execution.MavenRunnerParameters
import com.intellij.execution.testframework.sm.runner.SMTRunnerEventsListener
import com.intellij.execution.testframework.sm.runner.SMTestProxy
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout
import kotlin.time.Duration.Companion.minutes

val mavenResult = CompletableDeferred<Boolean>()
project.messageBus.connect().subscribe(SMTRunnerEventsListener.TEST_STATUS, object : SMTRunnerEventsListener {
    override fun onTestingFinished(testsRoot: SMTestProxy.SMRootTestProxy) { mavenResult.complete(testsRoot.isPassed) }
    override fun onTestingStarted(testsRoot: SMTestProxy.SMRootTestProxy) {}
    override fun onTestsCountInSuite(count: Int) {}
    override fun onTestStarted(test: SMTestProxy) {}
    override fun onTestFinished(test: SMTestProxy) {}
    override fun onTestFailed(test: SMTestProxy) {}
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
MavenRunConfigurationType.runConfiguration(project,
    MavenRunnerParameters(true, project.basePath!!, null,
        listOf("test", "-Dtest=MyServiceTest", "-Dspotless.check.skip=true"), emptyList()),
    null, null) {}
val passed = withTimeout(5.minutes) { mavenResult.await() }
println("Maven test: passed=$passed")
```

---

## Sync after pom.xml Change

After modifying `pom.xml`, trigger a full Maven re-import and wait for completion before compiling or running tests:

```kotlin[IU]
import org.jetbrains.idea.maven.project.MavenProjectsManager
import org.jetbrains.idea.maven.buildtool.MavenSyncSpec  // package: buildtool (NOT project) — IU-253+
import com.intellij.platform.backend.observation.Observation

val manager = MavenProjectsManager.getInstance(project)
manager.scheduleUpdateAllMavenProjects(
    MavenSyncSpec.full("post-pom-edit", explicit = true)
)
Observation.awaitConfiguration(project)  // suspends until sync + indexing fully complete
println("Maven sync complete — new deps resolved, safe to compile/inspect")
```

**Key notes:**
- `MavenSyncSpec.full()` forces re-reading all POM files (use after external edits)
- `MavenSyncSpec.incremental()` only syncs changed files (use for minor updates)
- `explicit = true` marks the sync as user-initiated (affects IDE progress indicators)
- `Observation.awaitConfiguration(project)` is required — otherwise `runInspectionsDirectly` shows false "cannot resolve symbol" errors from undownloaded deps
- ⚠️ `MavenSyncSpec` is in package `org.jetbrains.idea.maven.buildtool` — NOT `.project`

**Partial sync — only specific pom files:**
```kotlin[IU]
import org.jetbrains.idea.maven.project.MavenProjectsManager
import org.jetbrains.idea.maven.buildtool.MavenSyncSpec

val pomVf = findProjectFile("pom.xml")!!
MavenProjectsManager.getInstance(project).scheduleUpdateMavenProjects(
    MavenSyncSpec.full("update specific pom"),
    filesToUpdate = listOf(pomVf),
    filesToDelete = emptyList()
)
```

---

## Run Specific Test Class

Pass `-Dtest=ClassName` via `MavenRunnerSettings.mavenProperties`:

```kotlin[IU]
import org.jetbrains.idea.maven.execution.MavenRunner
import org.jetbrains.idea.maven.execution.MavenRunnerParameters
import org.jetbrains.idea.maven.execution.MavenRunnerSettings
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout
import kotlin.time.Duration.Companion.minutes

val done = CompletableDeferred<Boolean>()
val params = MavenRunnerParameters(
    true,
    project.basePath!!,
    null,
    listOf("test"),
    emptyList()
)
val runner = MavenRunner.getInstance(project)
val settings: MavenRunnerSettings = runner.settings.clone()
// Single test class:
settings.mavenProperties["test"] = "FeatureServiceTest"
// OR single method:  settings.mavenProperties["test"] = "FeatureServiceTest#shouldReturnFeature"
settings.mavenProperties["spotless.check.skip"] = "true"
runner.run(params, settings) { done.complete(true) }
val ok = withTimeout(5.minutes) { done.await() }
println("Test run completed: $ok")
// ⚠️ callback fires only on exit 0. For test failure details, use SMTRunnerEventsListener pattern above.
```

---

## ⚠️ ProcessBuilder("./mvnw") — LAST RESORT ONLY

Use `ProcessBuilder("./mvnw")` **only** when ALL of the following are true:
1. `pom.xml` was just modified in this session, AND
2. Maven sync was already triggered (`scheduleUpdateAllMavenProjects` + `awaitConfiguration`), AND
3. `MavenRunConfigurationType.runConfiguration()` with `dialog_killer: true` has already timed out (>2 min)

In all other cases, **use `MavenRunner` or `MavenRunConfigurationType`**.

```kotlin[IU]
// ⚠️ ONLY when Maven sync unavailable — e.g. immediately after pom.xml edit before sync
// ⚠️ ./mvnw (wrapper) not 'mvn' — system mvn is typically not installed
// ⚠️ Spring Boot test output can exceed 200k chars — NEVER print untruncated output
val process = ProcessBuilder("./mvnw", "test", "-Dtest=MyTest", "-Dspotless.check.skip=true")
    .directory(java.io.File(project.basePath!!))
    .redirectErrorStream(true).start()
val lines = process.inputStream.bufferedReader().readLines()
val exitCode = process.waitFor()
println("Exit: $exitCode | lines: ${lines.size}")
// ⚠️ Always capture BOTH ends: Spring context errors appear at START; BUILD FAILURE at END
println("--- First 30 lines (Spring context errors appear here) ---")
println(lines.take(30).joinToString("\n"))
println("--- Last 30 lines (Maven BUILD FAILURE summary appears here) ---")
println(lines.takeLast(30).joinToString("\n"))
```

---

## What NOT to Do

- **❌ `ProcessBuilder("./mvnw")` as primary pattern** — banned. Use `MavenRunner` or `MavenRunConfigurationType`.
- **❌ `ProcessBuilder("mvn")` without wrapper** — `mvn` is not installed. Always use `./mvnw`.
- **❌ Skip Maven sync after pom.xml edit** — without sync, imports show "cannot resolve symbol" false positives.
- **❌ Print untruncated Maven output** — Spring Boot tests generate 100k+ chars. Always use `.take(30)` + `.takeLast(30)`.
- **❌ Run multiple test classes in one `-Dtest=A,B,C,D`** — 4 Spring Boot tests × 25k chars each = MCP token overflow. Run one at a time.
