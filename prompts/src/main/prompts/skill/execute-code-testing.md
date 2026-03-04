Execute Code: Testing Patterns

Running tests via IDE runner and Maven/Gradle, Docker check, modal dialog recovery, inspection semantics, and @ControllerAdvice patterns.

# Execute Code: Testing Patterns

## ⚡ Incremental Compile Check After Bulk File Creation (~10s vs ~90s for mvnw test-compile)

After creating multiple files in a batch `writeAction`, run a project-wide incremental compile to catch errors fast — before paying for a full Gradle/Maven test cycle:

```kotlin
import com.intellij.task.ProjectTaskManager
import org.jetbrains.concurrency.await

val result = ProjectTaskManager.getInstance(project).buildAllModules().await()
println("Compile errors: ${result.hasErrors()}, aborted: ${result.isAborted()}")
// If hasErrors() == true: read and fix compile errors BEFORE running tests.
// This saves a full Gradle/Maven compile round-trip (~60-90s saved per error).
```

**⚠️ Use this BEFORE running `./gradlew test` after bulk file creation** — if any file has a package mismatch, typo, or missing import, this surfaces it in ~10s instead of waiting ~90s for the full test compile.

---

## ⚡ Fast Compile Check via runInspectionsDirectly (~5s vs ~90s for mvnw test-compile)

Run this BEFORE `./mvnw` to catch errors early:
```kotlin
val vf = findProjectFile("src/main/java/com/example/NewClass.java")!!
val problems = runInspectionsDirectly(vf)
if (problems.isEmpty()) println("OK: no compile errors")
else problems.forEach { (id, descs) -> descs.forEach { println("[$id] ${it.descriptionTemplate}") } }
```

**⚠️ Scope limitation**: `runInspectionsDirectly` is **file-scoped** — it only checks the single file you pass. After modifying a widely-used class (DTO, command, entity), also check dependent files or run `./mvnw test-compile` for project-wide verification.

**⚠️ Inspect MODIFIED files too** — not just newly created ones. After adding methods to an existing file (e.g., `findByFeature_Code` to a Spring Data repository), run `runInspectionsDirectly` on that file immediately. Spring Data JPA derived query names throw `QueryCreationException` at Spring context startup — the Spring Data plugin inspection catches these in ~5s, before `./mvnw test` (~90s).

**`runInspectionsDirectly` also catches Spring issues**: Duplicate `@Bean` definitions, missing `@Component` annotations, unresolved `@Autowired` dependencies. Run it on your `@Configuration` classes BEFORE `./mvnw test` to catch Spring bean override exceptions early.

---

## Maven Patterns Reference

For Maven-specific patterns (MavenRunner, MavenRunnerParameters, Maven sync after pom.xml changes), see `mcp-steroid://skill/execute-code-maven`.

---

## ❌ BANNED: Do NOT Use ProcessBuilder for Routine Maven/Gradle Builds or Tests

**ProcessBuilder("./mvnw", ...)** spawns a child process inside IntelliJ's JVM — this bypasses IDE process management, causes classpath conflicts, and produces 200k+ char output that overflows MCP token limits.

**Allowed alternatives (in priority order):**
1. **Maven IDE runner** — `MavenRunConfigurationType.runConfiguration()` (see below) — structured pass/fail, no token overflow
2. **Gradle IDE runner** — `ExternalSystemUtil.runTask()` with `GradleConstants.SYSTEM_ID` (see below)
3. **ProcessBuilder("./mvnw")** — ONLY when pom.xml was just modified AND the IDE runner's SMTRunnerEventsListener latch has already timed out

**`GeneralCommandLine("docker", ...)` and `ProcessBuilder("docker", ...)` inside exec_code are BANNED** — same reason as `./mvnw`: they spawn a child process inside IntelliJ's JVM.
- Docker socket availability: `java.io.File("/var/run/docker.sock").exists()` (no process spawn needed)
- Docker CLI operations (inspect, exec, etc.): use the **Bash tool** outside exec_code

---

## ⭐ PRIMARY: Maven IDE Runner — Structured Pass/Fail, No Token Overflow

**⚠️ Do NOT use ProcessBuilder("./mvnw") for routine test runs — use this IDE runner instead.**

Use this for Maven test execution. Always pass `dialog_killer: true` on the `steroid_execute_code` call to auto-dismiss modals:

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
    MavenRunnerParameters(true, project.basePath!!, "pom.xml",
        listOf("test", "-Dtest=FeatureReactionServiceTest", "-Dspotless.check.skip=true"), emptyList()),
    null, null) {}
val mvnPassed = withTimeout(5.minutes) { mavenResult.await() }
println("Maven IDE runner: passed=$mvnPassed")
// If modal dialog blocks: steroid_take_screenshot -> steroid_input dismiss -> retry. Do NOT fall back to ProcessBuilder.
```

---

## ⚠️ LAST-RESORT FALLBACK: ProcessBuilder("./mvnw") — Two Conditions Must BOTH Be True

**Only use ProcessBuilder("./mvnw") when ALL of the following are true:**
1. You just modified `pom.xml` in this session (IDE re-import dialog may block the runner latch), AND
2. The Maven IDE runner (`MavenRunConfigurationType.runConfiguration()`) has already timed out or failed

**If pom.xml was NOT modified: use the Maven IDE runner above — do NOT use ProcessBuilder.**

After pom.xml changes, IntelliJ triggers a Maven re-import dialog that blocks the IDE runner latch for up to 600s. First try: `MavenRunConfigurationType.runConfiguration()` with `dialog_killer: true`. If the latch times out after 2 minutes, only then use ProcessBuilder:
```kotlin
// ⚠️ Always use ./mvnw (Maven wrapper) not 'mvn' — system mvn is not installed
// ⚠️ CRITICAL: Spring Boot test output routinely exceeds 200k chars. NEVER print untruncated output.
// ⚠️ Do NOT use -q — Maven quiet mode suppresses "Tests run:" summary. Exit code 0 alone is NOT sufficient.
val process = ProcessBuilder("./mvnw", "test", "-Dtest=MyValidatorTest", "-Dspotless.check.skip=true")
    .directory(java.io.File(project.basePath!!))
    .redirectErrorStream(true).start()
val lines = process.inputStream.bufferedReader().readLines()
val exitCode = process.waitFor()
println("Exit: $exitCode | total output lines: ${lines.size}")
// ⚠️ Capture BOTH ends: Spring context / Testcontainers failures appear at the START;
// Maven BUILD FAILURE summary appears at the END. takeLast alone misses early errors.
println("--- First 30 lines (Spring context / Testcontainers errors appear here) ---")
println(lines.take(30).joinToString("\n"))
println("--- Last 30 lines (Maven BUILD FAILURE summary appears here) ---")
println(lines.takeLast(30).joinToString("\n"))
```

**⚠️ Run FAIL_TO_PASS tests one at a time** — NOT `-Dtest=Test1,Test2,Test3,Test4` all at once. 4 Spring Boot tests × 25k chars each = 100k+ chars → MCP token overflow.

---

## Docker Pre-Check — Run Proactively When Tests Use @Testcontainers
```kotlin
// Check Docker socket directly — no process spawn needed
val dockerOk = java.io.File("/var/run/docker.sock").exists()
println("Docker available: $dockerOk")
// If ALL FAIL_TO_PASS tests contain @Import(TestcontainersConfiguration.class)
//   OR extend AbstractIT/AbstractITBase/IntegrationTest, AND dockerOk=false →
//   SKIP the test run — go directly to ./mvnw test-compile verification.
//   These tests have NO H2 fallback: a DockerException is guaranteed.
// If dockerOk=false AND the test does NOT use Testcontainers: attempt it anyway.
//   Many "integration" tests use H2 in-memory DB and do NOT require Docker at all.
```

---

## Gradle Test Runner

PREFERRED over `ProcessBuilder("./gradlew")` inside exec_code — the latter spawns a nested Gradle daemon from within the IDE JVM, causing classpath conflicts:

```kotlin[IU]
import com.intellij.openapi.externalSystem.model.execution.ExternalSystemTaskExecutionSettings
import com.intellij.openapi.externalSystem.service.execution.ProgressExecutionMode
import com.intellij.openapi.externalSystem.task.TaskCallback
import com.intellij.openapi.externalSystem.util.ExternalSystemUtil
import org.jetbrains.plugins.gradle.util.GradleConstants
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout
import kotlin.time.Duration.Companion.minutes
val result = CompletableDeferred<Boolean>()
val s = ExternalSystemTaskExecutionSettings()
s.externalProjectPath = project.basePath!!
// After writing new source files: add "--rerun-tasks" to force test execution even if UP-TO-DATE
s.taskNames = listOf(":api:test", "--tests", "shop.api.composite.product.ProductCompositeServiceApplicationTests", "--rerun-tasks")
s.externalSystemIdString = GradleConstants.SYSTEM_ID.toString()
ExternalSystemUtil.runTask(s, com.intellij.execution.executors.DefaultRunExecutor.EXECUTOR_ID,
    project, GradleConstants.SYSTEM_ID,
    object : TaskCallback { override fun onSuccess() { result.complete(true) }; override fun onFailure() { result.complete(false) } },
    ProgressExecutionMode.IN_BACKGROUND_ASYNC, false)
val ok = withTimeout(5.minutes) { result.await() }; println("Gradle result: success=$ok")
// When success=false: read JUnit XML test results directly for failure details:
val testResultsDir = findProjectFile("build/test-results/test")
testResultsDir?.children?.filter { it.name.endsWith(".xml") }?.forEach { xmlFile ->
    val content = String(xmlFile.contentsToByteArray(), xmlFile.charset)
    val failures = Regex("<failure[^>]*>(.+?)</failure>", RegexOption.DOT_MATCHES_ALL)
        .findAll(content).map { it.groupValues[1].take(300) }.toList()
    if (failures.isNotEmpty()) println("FAIL ${xmlFile.name}: " + failures.first())
    else println("PASS ${xmlFile.name}")
}
```

---

## Verify @ControllerAdvice / @ExceptionHandler Exists

CRITICAL before writing controllers that throw custom exceptions — if no global handler exists, the API returns 500 instead of 404, breaking tests:
```kotlin[IU]
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.AnnotatedElementsSearch
val scope = GlobalSearchScope.projectScope(project)
val adviceAnnotation = readAction {
    JavaPsiFacade.getInstance(project).findClass("org.springframework.web.bind.annotation.ControllerAdvice", allScope())
        ?: JavaPsiFacade.getInstance(project).findClass("org.springframework.web.bind.annotation.RestControllerAdvice", allScope())
}
val adviceClasses = if (adviceAnnotation != null) {
    AnnotatedElementsSearch.searchPsiClasses(adviceAnnotation, scope).findAll().toList()
} else emptyList()
println("@ControllerAdvice classes: " + adviceClasses.map { it.qualifiedName })
// Find which exceptions each @ExceptionHandler covers:
adviceClasses.forEach { cls ->
    readAction {
        cls.methods.forEach { m ->
            val handler = m.annotations.firstOrNull { it.qualifiedName?.endsWith("ExceptionHandler") == true }
            if (handler != null) {
                val exTypes = handler.findAttributeValue("value")?.text ?: "(all)"
                println("  ${cls.name}.${m.name} handles: $exTypes → HTTP ${
                    m.annotations.firstOrNull { it.qualifiedName?.endsWith("ResponseStatus") == true }
                        ?.findAttributeValue("code")?.text ?: "?"
                }")
            }
        }
    }
}
// If adviceClasses is empty: the project has NO global exception handler.
// Controllers that throw custom exceptions will return 500. Add a @RestControllerAdvice class.
```

---

## Inspection Signal Semantics — Do NOT Misclassify

- **`[ConstantValue] Value ... is always 'null'`** on a DTO accessor in a test file → **CRITICAL BUG**: the DTO record is missing that component field. Do NOT dismiss as "pre-existing static analysis noise".
  - **`#ref` and `#loc` in ConstantValue output**: unresolved IntelliJ template placeholders. Look for DTO/record accessor calls in the test file that do NOT match any declared record component. Add the missing component.
- **`[ClassCanBeRecord]`** on a new DTO class → **REQUIRED**: convert to Java record (reference solution uses records).
- **`[ClassEscapesItsScope]`** on a `public` inner class inside Spring `@Service`/`@Repository` → **Expected**: safe to ignore.
- **`[GrazieInspectionRunner]`**, **`[DeprecatedIsStillUsed]`** → **Cosmetic**: low priority.

---

## Verification Gate — Run FAIL_TO_PASS Tests Before Marking Work Complete

```
./mvnw test -Dtest=ClassName -Dspotless.check.skip=true
# OR
./gradlew :module:test --tests "com.example.ClassName" --rerun-tasks --no-daemon
```

**⚠️ Compile success alone is NOT sufficient.**

**⚠️ FULL SUITE before `ARENA_FIX_APPLIED: yes`**: After FAIL_TO_PASS tests pass, ALSO run the complete test suite to catch regressions in other test classes.

**⚠️ Deprecation warnings ≠ errors**: Compiler output like `warning: 'getVirtualFilesByName(...)' is deprecated` is non-fatal — the script succeeded. Only retry on explicit `ERROR` responses with no `execution_id`.
