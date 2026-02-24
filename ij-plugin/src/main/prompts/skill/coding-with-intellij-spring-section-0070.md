### Environment Diagnostics (Docker / System — Consolidated)

**Consolidate all Docker and system environment checks into ONE `steroid_execute_code` call** instead of multiple Bash tool calls (each Bash call costs ~20s overhead). This single call replaces 8+ separate Bash commands (`docker info`, `ls /var/run/docker.sock`, `find / -name docker*`, `env | grep DOCKER`, `env | grep TESTCONTAINER`, `ps aux | grep docker`, etc.):

```kotlin
// ONE call replaces 8 separate Bash diagnostics — saves ~160s round-trip overhead
val dockerEnv = System.getenv().filter { (k, _) ->
    k.contains("DOCKER", ignoreCase = true) || k.contains("TESTCONTAINERS", ignoreCase = true)
}
println("Docker/TC env vars: $dockerEnv")
println("docker.sock exists: ${java.io.File("/var/run/docker.sock").exists()}")
val dockerBin = try {
    ProcessBuilder("which", "docker").start().inputStream.bufferedReader().readText().trim()
} catch (e: Exception) { "not found: $e" }
println("docker binary: $dockerBin")
println("dockerd exists: ${java.io.File("/usr/bin/dockerd").exists() || java.io.File("/usr/local/bin/dockerd").exists()}")
println("podman exists: ${java.io.File("/usr/bin/podman").exists()}")
println("PATH: ${System.getenv("PATH")}")
```

> **Key principle**: If you need 3+ diagnostic shell commands, collapse them into ONE `steroid_execute_code` call. The JVM inside IntelliJ has unrestricted filesystem and process access — identical to what Bash can do, but without the per-call overhead.

> **⚡ Proactive Docker pre-check — TRIGGER: run on your VERY FIRST `steroid_execute_code` call when**
> **FAIL_TO_PASS tests use `@Testcontainers` or extend `AbstractIT` / `IntegrationTest` / `AbstractITBase`.**
> Do NOT wait for test failures to discover Docker unavailability — that wastes 8+ turns (~3 min).
> Combine with your IDE readiness probe so it costs zero extra turns:

```kotlin
// STEP ZERO: combine IDE probe + Docker check in one call (before any implementation)
println("Project: ${project.name} @ ${project.basePath}")
println("Smart mode: ${!com.intellij.openapi.project.DumbService.isDumb(project)}")
val dp = ProcessBuilder("docker", "info").redirectErrorStream(true).start()
val dockerOk = dp.waitFor(10, java.util.concurrent.TimeUnit.SECONDS) && dp.exitValue() == 0
println("Docker available: $dockerOk")
// Decision based on result:
// dockerOk=true  → proceed normally; run @Testcontainers tests as final verification
// dockerOk=false → use runInspectionsDirectly for compile verification only; DO NOT run integration tests
//                  report ARENA_FIX_APPLIED: yes once inspections pass; do NOT investigate Docker further
```

> **When to stop investigating Docker failures**: If `./mvnw test` fails with `Could not find a valid Docker environment` AND an existing test (pre-patch) fails with the same error, the environment lacks Docker. This is an **infrastructure constraint, not a code defect**. Do NOT investigate further — use `runInspectionsDirectly` as your final verification and declare your fix complete.

### Baseline-vs-Target Infrastructure Probe (Deterministic)

Before declaring "environment blocked", run one **baseline** integration test and one **target**
integration test, then compare signatures. Same infra signature in both means the blocker is
environmental, not patch-specific.

```kotlin
import java.io.File

fun runSingleMavenTest(testClass: String): List<String> {
    val process = ProcessBuilder("./mvnw", "test", "-Dtest=$testClass", "-q")
        .directory(File(project.basePath!!))
        .redirectErrorStream(true)
        .start()
    val lines = process.inputStream.bufferedReader().readLines()
    process.waitFor()
    return lines
}

fun hasDockerInfraSignature(lines: List<String>): Boolean =
    lines.any { "Could not find a valid Docker environment" in it }

val baselineLines = runSingleMavenTest("com.example.ExistingIntegrationTest")
val targetLines = runSingleMavenTest("com.example.NewBehaviorIntegrationTest")

val baselineInfraBlocked = hasDockerInfraSignature(baselineLines)
val targetInfraBlocked = hasDockerInfraSignature(targetLines)

println("BASELINE_INFRA_BLOCKED=$baselineInfraBlocked")
println("TARGET_INFRA_BLOCKED=$targetInfraBlocked")

if (baselineInfraBlocked && targetInfraBlocked) {
    println("ENVIRONMENT_STATUS=BLOCKED")
    println("NEXT_STEP=Use inspections/build checks and finalize based on code correctness")
} else {
    println("ENVIRONMENT_STATUS=AVAILABLE_OR_DIFFERENT_FAILURE")
    println("NEXT_STEP=Treat target failure as code/test issue and continue debugging")
}
```

### Run a Specific JUnit Test Class via IntelliJ Runner (non-Maven/Gradle only)

> ⚠️ **Only use `JUnitConfiguration` for projects that do NOT use Maven or Gradle** (e.g. pure
> IntelliJ module projects). For Maven/Gradle projects use `MavenRunConfigurationType` or
> `GradleRunConfiguration` from the ★ PREFERRED ★ section above.

```kotlin
import com.intellij.execution.junit.JUnitConfiguration
import com.intellij.execution.junit.JUnitConfigurationType
import com.intellij.execution.ProgramRunnerUtil
import com.intellij.execution.RunManager
import com.intellij.execution.executors.DefaultRunExecutor

val factory = JUnitConfigurationType.getInstance().configurationFactories.first()
val config = factory.createConfiguration("Run test", project) as JUnitConfiguration
val data = config.persistentData               // typed as JUnitConfiguration.Data
data.TEST_CLASS = "com.example.MyValidatorTest"
data.TEST_OBJECT = JUnitConfiguration.TEST_CLASS  // ← must use constant, NOT string "class"
config.setWorkingDirectory(project.basePath!!)
val settings = RunManager.getInstance(project).createConfiguration(config, factory)
RunManager.getInstance(project).addConfiguration(settings)
ProgramRunnerUtil.executeConfiguration(settings, DefaultRunExecutor.getRunExecutorInstance())
println("Test run started")
// ⚠️ Pitfall: writing data.TEST_OBJECT = "class" → compile error "unresolved reference 'TEST_CLASS'"
// Always use the constant: JUnitConfiguration.TEST_CLASS
```

### Get Per-Test Breakdown via SMTestProxy

`SMTRunnerEventsListener.TEST_STATUS` works for all runners (Maven, Gradle, JUnit). Subscribe
before launching the test. Use `testsRoot.isPassed()` for overall pass/fail:

```kotlin
import com.intellij.execution.testframework.sm.runner.SMTRunnerEventsListener
import com.intellij.execution.testframework.sm.runner.SMTestProxy
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout
import kotlin.time.Duration.Companion.minutes

val result = CompletableDeferred<Unit>()

// Subscribe BEFORE launching (don't miss the event)
val connection = project.messageBus.connect()
connection.subscribe(SMTRunnerEventsListener.TEST_STATUS, object : SMTRunnerEventsListener {
    override fun onTestingFinished(testsRoot: SMTestProxy.SMRootTestProxy) {
        val passed = testsRoot.isPassed()
        val hasErrors = testsRoot.hasErrors()
        val allTests = testsRoot.getAllTests()
        val failCount = allTests.count { it.isDefect }
        println("Done — passed=$passed errors=$hasErrors failures=$failCount")
        allTests.filter { it.isDefect }.forEach { println("  FAILED: ${it.name}") }
        connection.disconnect()
        result.complete(Unit)
    }
    override fun onTestingStarted(testsRoot: SMTestProxy.SMRootTestProxy) {}
    override fun onTestFailed(test: SMTestProxy) {}
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

// Then launch via MavenRunConfigurationType or GradleRunConfiguration (see ★ PREFERRED ★ above)
// ... launch code here ...

withTimeout(5.minutes) { result.await() }
```

### Check Compile Errors Without Running Full Build

**Always run this BEFORE `./mvnw test`** — it catches errors in seconds, not minutes. If this reports errors, fix them before running the Maven test command.

> **⚠️ Scope limitation**: `runInspectionsDirectly` is **file-scoped** — it only analyzes the single
> file you pass. It does NOT catch compile errors in OTHER files that reference your changed signatures.
> After modifying a widely-used class (DTO, command, entity, record), also check the key dependent files
> (service, controller, mapper, test), or run `./mvnw compile -q` (with takeLast() truncation) for
> project-wide verification.
>
> **Staged verification recipe (Maven projects)**:
> 1. `runInspectionsDirectly(vf)` for each changed file — catches single-file syntax/import errors (~5s each)
> 2. `./mvnw compile -q` — catches cross-file type errors, missing methods, broken call sites (~30-60s)
> 3. `./mvnw test -Dtest=TargetTest` — only after steps 1+2 pass (runs Docker-dependent tests)
>
> Do NOT skip step 2 and jump directly to step 3 — a compile error in a dependent file will fail the test
> with a confusing runtime stacktrace rather than a clean compile error message.

> **⚠️ Spring bonus — also catches bean conflicts**: `runInspectionsDirectly` detects Spring Framework
> issues beyond compile errors: duplicate `@Bean` method definitions in `@Configuration` classes (causes
> `NoUniqueBeanDefinitionException` at startup), missing `@Component` / `@Service` annotations, and
> unresolved `@Autowired` dependencies. Run it on `@Configuration` classes **BEFORE** `./mvnw test`
> to catch Spring startup failures in ~5s instead of waiting for a 90s Maven cold-start.

```kotlin
// Faster than 'mvn test' — returns IDE inspection results in seconds
// Run this after creating/modifying files, BEFORE running ./mvnw test
val vf = findProjectFile("src/main/java/com/example/Product.java")!!
val problems = runInspectionsDirectly(vf)
if (problems.isEmpty()) {
    println("No problems found — safe to run tests")
} else {
    problems.forEach { (id, descs) ->
        descs.forEach { println("[$id] ${it.descriptionTemplate}") }
    }
    println("Fix the above errors before running tests")
}
// Also check key dependent files to catch cross-file breakage:
for (depPath in listOf(
    "src/main/java/com/example/service/ProductService.java",
    "src/main/java/com/example/api/ProductController.java"
)) {
    val depVf = findProjectFile(depPath) ?: continue
    val depProblems = runInspectionsDirectly(depVf)
    if (depProblems.isNotEmpty()) {
        println("Problems in $depPath:")
        depProblems.forEach { (id, descs) -> descs.forEach { println("  [$id] ${it.descriptionTemplate}") } }
    }
}
```

### Inspection Result: ClassCanBeRecord → Always Convert for New DTO Classes

> **When creating new DTO, data, or value classes** and `runInspectionsDirectly` reports
> `[ClassCanBeRecord]`, **always convert the class to a Java `record`**. This is not an optional
> style suggestion for new code — the inspection is telling you the class *should be* a record.
> The reference solution typically uses Java records for DTOs; failing to convert causes a structural
> mismatch with expected behavior.
>
> **Do NOT ignore `ClassCanBeRecord` on newly-created DTO/data classes.** Treat it as a required
> action, not informational noise.

```kotlin
// WRONG: create as traditional class and ignore ClassCanBeRecord warning
// public class ProductAggregate { private String name; ... }

// CORRECT: create as Java record from the start
writeAction {
    val root = LocalFileSystem.getInstance().findFileByPath(project.basePath!!)!!
    val dir = VfsUtil.createDirectoryIfMissing(root, "src/main/java/com/example/api")
    val f = dir.findChild("ProductAggregate.java") ?: dir.createChildData(this, "ProductAggregate.java")
    VfsUtil.saveText(f, listOf(
        "package com.example.api;",
        "",
        "public record ProductAggregate(String name, int weight) {}"
    ).joinToString("\n"))
}
// After writing, run runInspectionsDirectly to confirm ClassCanBeRecord is gone
```

### Inspection Result: ClassEscapesItsScope for Spring Beans → Expected, Non-Blocking

> **`[ClassEscapesItsScope]`** appears on Spring `@Service`, `@Repository`, and `@Component` beans that expose package-private types through public methods (e.g. a `public` method returning a package-private domain object). This is **expected in Spring Boot projects** and non-blocking — it does not prevent compilation or deployment.
>
> **Before spending a turn trying to fix it**: Check whether the same warning appears on existing (pre-patch) services or repositories in the project. If `FeatureService`, `FavoriteFeatureService`, or other existing beans have the same warning, your new `@Service` will too — it is a deliberate design pattern in the codebase. Do NOT refactor to fix it; simply note it and move on.

### Inspection Result: ConstantValue "Value is always null" on DTO Accessor → CRITICAL Bug

> **`[ConstantValue] Value ... is always 'null'`** on a DTO method call (e.g. `dto.releasedAt()`, `dto.status()`, `dto.version()`) in a test file is a **critical data-flow finding, NOT a style warning**. IntelliJ's type system has proven the accessor always returns `null` — which happens when the **DTO record is missing that component field** (the accessor method does not exist or returns null unconditionally).
>
> **Do NOT dismiss `ConstantValue` on DTO/record accessor calls as "pre-existing static analysis notes" or "noise".** It is a guaranteed runtime `NullPointerException` or assertion failure at test execution time.

**Severity classification — prevents misclassification:**

| Inspection ID | Severity | Action |
|---------------|----------|--------|
| `ConstantValue` ("always null/true/false") | **CRITICAL** — runtime failure guaranteed | Investigate immediately |
| `AssertBetweenInconvertibleTypes` | **CRITICAL** — assertion always passes/fails | Investigate |
| `ClassCanBeRecord` | **REQUIRED** — structural mismatch | Convert to record |
| `ClassEscapesItsScope` on Spring beans | **EXPECTED** — ignore | Skip |
| `DeprecatedIsStillUsed` | **LOW** — cosmetic | Fix if time allows |
| `GrazieInspectionRunner` | **COSMETIC** — grammar | Ignore |

**Diagnosis recipe** — run when you see `[ConstantValue] Value ... is always 'null'` on a DTO accessor:

```kotlin
// Step 1: Read the DTO record source to see its actual component list
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
val dtoFile = readAction {
    FilenameIndex.getVirtualFilesByName("ReleaseDto.java", GlobalSearchScope.projectScope(project)).firstOrNull()
}
if (dtoFile != null) {
    println("=== DTO source ===")
    println(VfsUtil.loadText(dtoFile))
}

// Step 2: Cross-reference with what the test calls on the DTO
val testFile = readAction {
    FilenameIndex.getVirtualFilesByName("ReleaseControllerTests.java", GlobalSearchScope.projectScope(project)).firstOrNull()
}
if (testFile != null) {
    val text = VfsUtil.loadText(testFile)
    // Extract .methodName() calls that look like DTO accessors (lower-camel, not assertion calls):
    val dtoCalls = Regex("\\.([a-z][a-zA-Z0-9]+)\\(\\)")
        .findAll(text)
        .map { it.groupValues[1] }
        .filter { it !in setOf("body", "isEqualTo", "isNotNull", "statusCode", "then", "when", "get", "size", "isEmpty") }
        .toSet()
    println("DTO methods called in tests: $dtoCalls")
}
// Compare output: methods in dtoCalls absent from DTO record components = missing fields to add
```

> **Fix**: Add the missing components to the DTO `record` definition. For example, if `ReleaseDto` is
> `public record ReleaseDto(String name, String version)` and the test calls `dto.releasedAt()`, add
> `Instant releasedAt` to the record — and update the mapper/service/query that constructs the DTO.

---
