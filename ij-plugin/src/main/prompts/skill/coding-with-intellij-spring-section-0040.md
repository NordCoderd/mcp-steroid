### Check Pending VCS Changes (Prefer Over `git diff` Shell Calls)

**PREFERRED over `ProcessBuilder("git", "diff", "HEAD", "--name-only")`** — avoids blocking the script executor thread and works correctly even inside IDE-managed VFS.

```kotlin
// Check which files have pending (uncommitted) changes
val changes = readAction {
    com.intellij.openapi.vcs.changes.ChangeListManager.getInstance(project)
        .allChanges.mapNotNull { it.virtualFile?.path }
}
println(if (changes.isEmpty()) "Clean slate — no pending changes" else "Modified files:\n" + changes.joinToString("\n"))
```

Use this at the start of arena tasks to detect whether a previous agent slot already modified files — avoids overwriting work done by a parallel agent.

**Multi-agent step 2: after VCS check, verify required classes exist with correct FQN** (changed files ≠ correct fix — a prior agent may have created files in the wrong package):

```kotlin
// After detecting modified files, check that required classes actually resolve
val scope = GlobalSearchScope.projectScope(project)
val required = listOf(
    "shop.api.core.product.Product",
    "shop.api.composite.product.ProductAggregate"
)  // ← replace with your task's required FQNs
val missing = required.filter {
    readAction { JavaPsiFacade.getInstance(project).findClass(it, scope) } == null
}
println(if (missing.isEmpty()) "All required classes present — run tests"
        else "STILL MISSING (must create): " + missing.joinToString(", "))
```

### Read JUnit XML Test Results After ExternalSystemUtil `success=false`

When `ExternalSystemUtil.runTask()` returns `success=false` **do NOT immediately fall back to `ProcessBuilder("./gradlew")`**. Read the JUnit XML results directly from `build/test-results/test/` instead — this gives you structured failure details without spawning a nested Gradle daemon:

```kotlin
import com.intellij.openapi.vfs.VfsUtil

// Adjust path for your module (e.g. "microservices/product-service/build/test-results/test")
val testResultsDir = findProjectFile("build/test-results/test")
if (testResultsDir == null) {
    println("No test-results dir — tests may not have run (compilation error stopped before test phase)")
} else {
    testResultsDir.children.filter { it.name.endsWith(".xml") }.forEach { xmlFile ->
        val content = VfsUtil.loadText(xmlFile)
        val failures = Regex("""<failure[^>]*>(.+?)</failure>""", RegexOption.DOT_MATCHES_ALL)
            .findAll(content).map { it.groupValues[1].take(300) }.toList()
        if (failures.isNotEmpty()) println("FAIL ${xmlFile.name}:\n" + failures.first())
        else println("PASS ${xmlFile.name}")
    }
}
```

> **⚠️ CRITICAL: `BUILD SUCCESSFUL` with ProcessBuilder exit=0 does NOT mean tests ran and passed.**
> Gradle exits 0 when it completes all *requested tasks* without error — but if the test task was
> UP-TO-DATE, or a compilation error stopped execution before the test phase, no tests ran at all.
> The **only** confirmation that tests executed and passed is `Tests run: X, Failures: 0, Errors: 0`
> appearing in the output. Absence of this line means tests did not run — do NOT declare success.

**⚠️ VFS → Git sync lag**: After bulk `writeAction { VfsUtil.saveText(...) }` edits, git-based tools (subprocess `git diff`, `ProcessBuilder("git", ...)`) may see stale content because VFS changes haven't been flushed to disk yet. Always call `LocalFileSystem.getInstance().refresh(false)` (synchronous) after bulk VFS edits, BEFORE running any git subprocess or checking git diff:

```kotlin
// Apply bulk changes
writeAction {
    files.forEach { (vf, content) -> VfsUtil.saveText(vf, content) }
}
// Flush VFS to disk — ensures git diff / shell tools see the updates
LocalFileSystem.getInstance().refresh(false)

// Now git-based checks are accurate:
val result = ProcessBuilder("git", "diff", "--name-only")
    .directory(java.io.File(project.basePath!!)).start()
println(result.inputStream.bufferedReader().readText())
```

### Add a Method to an Existing Java Class via PSI (Safer Than VfsUtil.saveText for Partial Updates)

**`VfsUtil.saveText()` replaces the ENTIRE file** — if you only need to add one method, use PSI surgery instead. This avoids overwriting code you haven't read and reduces the risk of accidentally losing other methods.

```kotlin
import com.intellij.psi.JavaPsiFacade
import com.intellij.openapi.command.WriteCommandAction

// Find the class to modify
val psiClass = readAction {
    JavaPsiFacade.getInstance(project).findClass(
        "org.springframework.samples.petclinic.service.UserServiceImpl",
        GlobalSearchScope.projectScope(project)
    )
}
if (psiClass != null) {
    val factory = JavaPsiFacade.getElementFactory(project)
    // Build method text using concatenation — avoid 'import ...' at line-start in triple-quoted strings
    val methodText = "private void validatePassword(String password) {\n" +
        "    if (password == null || password.isEmpty()) {\n" +
        "        throw new IllegalArgumentException(\"Password must not be empty\");\n" +
        "    }\n" +
        "}"
    val newMethod = readAction { factory.createMethodFromText(methodText, psiClass) }
    WriteCommandAction.runWriteCommandAction(project) {
        psiClass.add(newMethod)
    }
    println("Method added to ${psiClass.qualifiedName}")
    // Run inspection to verify syntax
    val vf = psiClass.containingFile.virtualFile
    val problems = runInspectionsDirectly(vf)
    if (problems.isEmpty()) println("No compile errors") else problems.forEach { (id, ds) -> ds.forEach { println("[$id] ${it.descriptionTemplate}") } }
} else println("Class not found — check the FQN")
```

### Add Maven Dependencies to pom.xml via VFS (Reliable Pattern)

**PREFER exec_code VFS write over native Edit tool** for pom.xml changes. VFS write triggers IDE file-change notification immediately, making Maven auto-import more reliable.

```kotlin
// Step 1: Add dependency via VFS text replace
val pomFile = findProjectFile("pom.xml")!!
val content = VfsUtil.loadText(pomFile)

// Print excerpt before replacing — catch whitespace issues before they waste a turn:
val idx = content.indexOf("</dependencies>")
println("EXCERPT (around </dependencies>):\n" + content.substring((idx - 50).coerceAtLeast(0), idx + 20))

val newDep = """
    <dependency>
        <groupId>io.jsonwebtoken</groupId>
        <artifactId>jjwt-api</artifactId>
        <version>0.12.6</version>
    </dependency>"""  // ← adjust groupId/artifactId/version as needed
val updated = content.replace("</dependencies>", "$newDep\n  </dependencies>")
check(updated != content) { "replace matched nothing — pom.xml may not have a </dependencies> tag" }
writeAction { VfsUtil.saveText(pomFile, updated) }
println("pom.xml updated — trigger Maven sync next")
// Step 2: After writing, trigger Maven sync (next section)
```

### Trigger Maven Re-import After pom.xml Changes

```kotlin
import org.jetbrains.idea.maven.project.MavenProjectsManager
import org.jetbrains.idea.maven.buildtool.MavenSyncSpec  // ← correct package for IU-253+; NOT .project.MavenSyncSpec
import com.intellij.platform.backend.observation.Observation

// After editing pom.xml: schedule sync AND AWAIT it with Observation.awaitConfiguration()
// This is the production-grade API used in Android Studio — avoids 600s modal timeouts.
val manager = MavenProjectsManager.getInstance(project)
manager.scheduleUpdateAllMavenProjects(MavenSyncSpec.full("post-pom-edit", false))
Observation.awaitConfiguration(project)   // suspends until Maven sync + indexing fully complete
println("Maven sync and indexing complete — safe to run tests now")
// ⚠️ If MavenSyncSpec cannot be resolved, fall back to:
//   ProcessBuilder("./mvnw", "dependency:resolve").directory(java.io.File(project.basePath!!)).start().waitFor()
```

> **`Observation.awaitConfiguration()`** is the canonical way to await any background IDE activity
> (Maven sync, Gradle import, indexing). It is suspend-compatible and handles cancellation.
> This replaces ad-hoc polling loops or `waitForSmartMode()` after build-file changes.

### Editing Existing Files via VFS (PREFERRED over native Edit tool)

**Use `VfsUtil.loadText` + `VfsUtil.saveText` for editing existing files** when IDE change notification matters (e.g., pom.xml edits that trigger Maven auto-import, or any file that the IDE needs to re-parse). The native `Edit` tool writes directly to disk, bypassing IntelliJ's VFS layer — Maven auto-import may not trigger.

```kotlin
// ✓ PREFERRED: Edit existing file via VFS — triggers IDE change notification
val vf = findProjectFile("pom.xml")!!
val content = VfsUtil.loadText(vf)             // read OUTSIDE writeAction
// Print the target slice first to verify exact whitespace/content before replacing:
val idx = content.indexOf("</dependencies>")
println("Target slice:\n" + content.substring(maxOf(0, idx - 100), minOf(content.length, idx + 50)))
val newDeps = """
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-data-jpa</artifactId>
    </dependency>
    <dependency>
        <groupId>com.h2database</groupId>
        <artifactId>h2</artifactId>
        <scope>test</scope>
    </dependency>
""".trimIndent()
val updated = content.replace("</dependencies>", "$newDeps\n</dependencies>")
check(updated != content) { "pom.xml replace matched nothing — check exact whitespace" }
writeAction { VfsUtil.saveText(vf, updated) }  // write INSIDE writeAction — triggers VFS notification
println("pom.xml updated — IDE change notification fired")
// Then trigger Maven sync (see above)
```

> **Why VFS over native Edit?**
> - VFS write → IntelliJ detects the change → Maven auto-import triggers automatically
> - Native `Edit` tool → writes to disk directly → IntelliJ may miss the change → Maven sync needed manually
> - After VFS write of pom.xml, still run `MavenSyncSpec.full(...)` + `Observation.awaitConfiguration()` to be safe

### Read Maven Project Model (Dependencies, Effective POM)

**Prefer over `File(basePath, "pom.xml").readText()`** — respects parent POM inheritance and property interpolation. Useful for checking which version of a library is in use, or whether a dependency is present.

```kotlin
import org.jetbrains.idea.maven.project.MavenProjectsManager

// Query Maven project model (effective POM — includes parent POM inheritance and property resolution)
val mavenManager = MavenProjectsManager.getInstance(project)
val rootProject = mavenManager.rootProjects.firstOrNull() ?: error("No Maven project found")
println("Project: ${rootProject.mavenId.groupId}:${rootProject.mavenId.artifactId}:${rootProject.mavenId.version}")
// List all resolved dependencies (includes dependencies inherited from parent POM):
rootProject.dependencies.forEach { dep ->
    println("  dep: ${dep.groupId}:${dep.artifactId}:${dep.version} scope=${dep.scope}")
}
// Check if a specific dependency exists (e.g. to detect Jakarta vs javax):
val hasLiquibase = rootProject.dependencies.any { it.groupId == "org.liquibase" }
println("Has Liquibase: $hasLiquibase")
```

### IDE-Native Project Build Verification (ProjectTaskManager)

**Preferred over `./mvnw test-compile`** — compiles through IntelliJ's build system, gives structured results, and avoids spawning a child Maven process. Use when you want to verify project-wide compilation without running any tests.

```kotlin
import com.intellij.task.ProjectTaskManager
import com.intellij.openapi.module.ModuleManager
import kotlinx.coroutines.future.await

val modules = ModuleManager.getInstance(project).modules
val result = ProjectTaskManager.getInstance(project).build(modules).await()
println("Build errors: ${result.hasErrors()}")
println("Build aborted: ${result.isAborted}")
// result.hasErrors() == false means project-wide compile passed
```

> **Note**: `ProjectTaskManager.build()` compiles *all* modules. For a quick single-file check, use
> `runInspectionsDirectly(vf)` first (seconds), then fall back to this for cross-file verification.

### Run Tests via IntelliJ IDE Runner ★ PREFERRED ★

> **⚠️ Arena / Docker environment**: Tests that use `@Testcontainers` require Docker-in-Docker to be
> available. Most arena environments support this — **do NOT skip running tests just because you see
> `@Testcontainers`**. Only treat Docker as unavailable if you run a baseline test (a test that existed
> BEFORE your changes) and it fails with `Could not find a valid Docker environment` — then it's an
> infrastructure constraint, not a code defect. Use `runInspectionsDirectly()` as your final check
> in that case and declare your fix complete.

**Always prefer this over `./mvnw test` or `./gradlew test`.** Running tests through the IDE
runner is equivalent to clicking the green ▶ button next to a test class or method. Benefits:

- **No 200k-char truncation problem** — pass/fail from `isPassed()` on the SMTestProxy root
- **Structured results** in the IDE Test Results window — individual failures navigable
- **Works for any build system** — Maven, Gradle, or plain JUnit

> ⚠️ **CRITICAL**: `JUnitConfiguration` (from `com.intellij.execution.junit`) is for **standalone
> JUnit tests** that do NOT need Maven/Gradle. For Maven or Gradle projects use the APIs below —
> otherwise dependencies won't be resolved and the test will fail to compile.
