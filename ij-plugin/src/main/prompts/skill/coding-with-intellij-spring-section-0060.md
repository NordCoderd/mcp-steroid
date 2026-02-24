### Run Unit Tests via Maven Wrapper (PRIMARY after pom.xml edits; fallback otherwise)

**Use `./mvnw` as your PRIMARY test runner whenever you have edited `pom.xml`** in the current session — the Maven IDE runner triggers a re-import modal that blocks for up to 600 seconds after build file changes. For sessions where `pom.xml` was NOT modified, prefer the IDE runner above (avoids 200k-char output truncation).

> **⚠️ CRITICAL — Output Truncation Required**: Spring Boot integration test output routinely exceeds
> **200k characters** (Spring context startup ~100 lines, Flyway migration logs, Testcontainers Docker
> pull logs, full stack traces). Printing the full output causes MCP token limit errors.
> **Always use `takeLast()` to read only the relevant tail**:

```kotlin
// ⚠️ FALLBACK ONLY — use the JUnit or Maven IDE runner below when possible
// ⚠️ Always use ./mvnw (Maven wrapper) not 'mvn' — system mvn is not installed in arena environments
// ⚠️ NEVER print process.inputStream.bufferedReader().readText() — Spring Boot output can be 200k+ chars
val process = ProcessBuilder("./mvnw", "test", "-Dtest=MyValidatorTest", "-Dspotless.check.skip=true", "-q")
    .directory(java.io.File(project.basePath!!))
    .redirectErrorStream(true).start()
val lines = process.inputStream.bufferedReader().readLines()
val exitCode = process.waitFor()
println("Exit: $exitCode | total output lines: ${lines.size}")
// ⚠️ Capture BOTH ends: Spring context / Testcontainers errors appear at the START of output;
// Maven BUILD FAILURE summary appears at the END. Using takeLast alone loses early failures.
println("--- First 30 lines (Spring context / Testcontainers errors appear here) ---")
println(lines.take(30).joinToString("\n"))
println("--- Last 30 lines (Maven BUILD FAILURE summary appears here) ---")
println(lines.takeLast(30).joinToString("\n"))
```

> **⚠️ Run FAIL_TO_PASS tests one at a time** — not all at once. Running multiple Spring Boot tests in
> one Maven call multiplies startup log output (4 tests × 25k chars each = 100k+ chars), causing MCP
> token overflow errors that require multi-step Bash parsing to recover from. Always run individually:
> `-Dtest=SingleTestClass` not `-Dtest=Test1,Test2,Test3,Test4`.

> **⚠️ After FAIL_TO_PASS tests pass: run the FULL test class for regression check**.
> Using method-level filtering (`-Dtest=ClassName#method1+method2`) runs only the new tests —
> pre-existing tests updated by the test patch are not exercised and can silently regress.
> Always run class-level: `-Dtest=UserRestControllerTests` (no `#method` suffix) to exercise
> all methods and confirm no regressions before outputting `ARENA_FIX_APPLIED: yes`.

> **⚠️ After FAIL_TO_PASS class passes: run the FULL project test suite** (`./mvnw test`, no `-Dtest=` filter)
> to catch regressions in OTHER test classes that share service or data layer code with FAIL_TO_PASS.
> Example: adding password validation to `UserServiceImpl` may break `AbstractUserServiceTests`,
> `UserServiceJdbcTests`, `UserServiceJpaTests`, `UserServiceSpringDataJpaTests` if they use `"password"`
> as test data. These tests are not in FAIL_TO_PASS but will fail after your change.
> **Before outputting `ARENA_FIX_APPLIED: yes`**: (1) Run class-level FAIL_TO_PASS tests → pass,
> (2) Search for `Abstract*Tests` and other test classes sharing the same data, update them if needed,
> (3) Run `./mvnw test` (full suite, no filter) → exit 0. Only then output the success marker.

> **⚠️ When take/takeLast is not enough** (output still exceeds limit after first+last 30 lines):
> Use keyword filtering to extract only signal lines from verbose Spring Boot / Testcontainers output:

```kotlin
// Keyword-filtered Maven output — use when verbose Spring Boot output exceeds MCP token limit
// even after take(30)+takeLast(30). Prevents multi-step Bash parsing recovery (saves 3-5 turns).
val process = ProcessBuilder("./mvnw", "test", "-Dtest=OnlyOneTestClass", "-Dspotless.check.skip=true", "-q")
    .directory(java.io.File(project.basePath!!)).redirectErrorStream(true).start()
val lines = process.inputStream.bufferedReader().readLines()
val completed = process.waitFor(180, java.util.concurrent.TimeUnit.SECONDS)
val keywords = listOf("Tests run:", "FAILED", "ERROR", "Caused by:", "BUILD", "Could not", "Exception in")
println("Exit: ${if (completed) process.exitValue() else "TIMEOUT"} | total lines: ${lines.size}")
println("--- First 20 lines (Spring startup errors) ---")
lines.take(20).forEach(::println)
println("--- Signal lines only ---")
lines.filter { l -> keywords.any { k -> k in l } }.take(50).forEach(::println)
println("--- Last 15 lines (Maven BUILD FAILURE) ---")
lines.takeLast(15).forEach(::println)
```

Similarly for `test-compile` (project-wide dependency check, faster than full test run):
```kotlin
val process = ProcessBuilder("./mvnw", "test-compile", "-Dspotless.check.skip=true", "-q")
    .directory(java.io.File(project.basePath!!))
    .redirectErrorStream(true).start()
val lines = process.inputStream.bufferedReader().readLines()
val exitCode = process.waitFor()
println("Compile exit: $exitCode | lines: ${lines.size}")
// Compile errors may appear anywhere — capture both ends for full context
println(lines.take(20).joinToString("\n"))
println("---")
println(lines.takeLast(20).joinToString("\n"))
```

> **⚠️ Deprecation warnings are non-fatal**: Output like `warning: 'getVirtualFilesByName(String, GlobalSearchScope)' is deprecated` does not indicate failure — the script ran successfully. Do NOT retry just because of deprecation warnings; only retry on actual `ERROR` responses.

### Run Gradle Tests via ExternalSystemUtil ★ PREFERRED for Gradle ★

> **⚠️ Anti-pattern**: Never use `ProcessBuilder("./gradlew", ...)` **inside** `steroid_execute_code`.
> This spawns a nested Gradle daemon from within the IDE JVM, causing classpath conflicts and
> resource exhaustion. Use the IntelliJ ExternalSystem API below instead. If the IDE runner is
> unavailable, fall back to the Bash tool (outside exec_code) — NOT ProcessBuilder inside exec_code.

```kotlin
import com.intellij.openapi.externalSystem.model.execution.ExternalSystemTaskExecutionSettings
import com.intellij.openapi.externalSystem.service.execution.ProgressExecutionMode
import com.intellij.openapi.externalSystem.task.TaskCallback
import com.intellij.openapi.externalSystem.util.ExternalSystemUtil
import org.jetbrains.plugins.gradle.util.GradleConstants
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout
import kotlin.time.Duration.Companion.minutes

val result = CompletableDeferred<Boolean>()

val settings = ExternalSystemTaskExecutionSettings().apply {
    externalProjectPath = project.basePath!!
    taskNames = listOf(":api:test", "--tests", "com.example.api.ProductControllerTest")
    externalSystemIdString = GradleConstants.SYSTEM_ID.toString()
    vmOptions = "-Xmx512m"
}

ExternalSystemUtil.runTask(
    settings,
    com.intellij.execution.executors.DefaultRunExecutor.EXECUTOR_ID,
    project,
    GradleConstants.SYSTEM_ID,
    object : TaskCallback {
        override fun onSuccess() { result.complete(true) }
        override fun onFailure() { result.complete(false) }
    },
    ProgressExecutionMode.IN_BACKGROUND_ASYNC,
    false
)
val gradleSuccess = withTimeout(5.minutes) { result.await() }
println("Gradle result: success=$gradleSuccess")
```

> If the IDE runner is not available or times out, use the Bash tool **outside exec_code**:
> `./gradlew :api:test --tests "com.example.api.ProductControllerTest" --no-daemon -q`
> Do NOT use ProcessBuilder inside exec_code for this.

### Run Gradle Tests via ProcessBuilder (fallback — use Bash tool instead when possible)

> **⚠️ FALLBACK ONLY**: Prefer the ExternalSystemUtil approach above. If you must use ProcessBuilder
> (e.g. when IDE runner hangs), note that this spawns a child Gradle process inside the IDE JVM.
> When possible, use the Bash tool outside exec_code instead (avoids classpath conflicts).

For **Gradle** projects, use `./gradlew` with `--tests` for targeted test class execution.

> **⚠️ CRITICAL — Output Truncation Required**: Same as Maven — Gradle integration test output can be 200k+ chars. **Always use `takeLast()` and `take()` to capture both ends.**

> **⚠️ UP-TO-DATE false-positive after writing new files**: After creating new source files via `writeAction { VfsUtil.saveText(...) }`, Gradle may report the test task as `UP-TO-DATE` and skip executing tests entirely — yet still exit with code 0 and print `BUILD SUCCESSFUL`. The task inputs appear unchanged from Gradle's perspective because the compilation cache was not invalidated. **Always add `--rerun-tasks` to the FIRST Gradle test invocation after writing new source files.** If you see `BUILD SUCCESSFUL` with no `Tests run:` line in the output, add `--rerun-tasks` and rerun.

```kotlin
// Run a specific test class in a specific Gradle submodule
// ⚠️ After writing new source files: ALWAYS add --rerun-tasks to the first test run
// to avoid the UP-TO-DATE false-positive (Gradle skips tests silently, exits 0)
val proc = ProcessBuilder("./gradlew", ":product-service:test",
    "--tests", "com.example.product.ProductServiceTest",
    "--rerun-tasks", "--no-daemon")
    .directory(java.io.File(project.basePath!!))
    .redirectErrorStream(true).start()
val lines = proc.inputStream.bufferedReader().readLines()
val exit = proc.waitFor()
println("Exit: $exit | total lines: ${lines.size}")
// ⚠️ Capture BOTH ends: Spring context / startup errors appear at the START;
// Gradle BUILD FAILED summary with test counts appears at the END.
println("--- First 30 lines (Spring context / Testcontainers errors appear here) ---")
println(lines.take(30).joinToString("\n"))
println("--- Last 30 lines (Gradle BUILD FAILED summary appears here) ---")
println(lines.takeLast(30).joinToString("\n"))
```

```kotlin
// Run ALL tests in a module (when no specific class is needed):
val proc = ProcessBuilder("./gradlew", ":product-service:test", "--no-daemon", "-q")
    .directory(java.io.File(project.basePath!!))
    .redirectErrorStream(true).start()
val lines = proc.inputStream.bufferedReader().readLines()
val exit = proc.waitFor()
println("Exit: $exit | lines: ${lines.size}")
println(lines.take(30).joinToString("\n"))
println("---")
println(lines.takeLast(30).joinToString("\n"))
```

**Gradle vs Maven cheat sheet:**

| Action | Maven | Gradle |
|--------|-------|--------|
| Run one test class | `-Dtest=SimpleClassName` | `--tests "com.example.FullyQualifiedClassName"` |
| Run one test method | `-Dtest=ClassName#method` | `--tests "com.example.ClassName.method"` |
| Target a module | `-pl product-service` | `:product-service:test` |
| Skip spotless | `-Dspotless.check.skip=true` | (not needed usually) |
| No daemon | n/a | `--no-daemon` |
| Quiet output | `-q` | `-q` |
| Force re-run (post-write) | (Maven always reruns) | `--rerun-tasks` |
