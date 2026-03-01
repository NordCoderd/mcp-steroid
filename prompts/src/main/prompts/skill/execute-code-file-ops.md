Execute Code: File Operations

First-call readiness probe, Spring combined startup, batch file reads, findProjectFile pitfall, and FilenameIndex patterns.

# Execute Code: File Operations

## ⚡ First-Call Readiness Probe

Verify IDE + MCP connectivity before heavy ops:
```kotlin
println("IDE ready: ${project.name}")
println("Base path: ${project.basePath}")
println("Smart mode: ${!com.intellij.openapi.project.DumbService.isDumb(project)}")
```

> **Once smart mode is confirmed, do NOT re-probe before each subsequent operation.** Combine the readiness check with your first real action to save round-trips (~20s each). Only re-probe if you triggered a Maven import or other index-invalidating step.

---

## ⚡ Spring Boot / Maven Combined Startup Call

Combine readiness + Docker + VCS discovery in ONE call instead of 3 separate calls (saves ~60s). **Skip the Docker check if the scenario is pure file-creation (no @Testcontainers, no Docker in FAIL_TO_PASS tests)** — the check adds 10-15s with no benefit for those cases:
```kotlin
// Recommended FIRST exec_code call for any Spring Boot / Maven task:
println("Project: ${project.name}")
println("Smart: ${!com.intellij.openapi.project.DumbService.isDumb(project)}")
val dp = ProcessBuilder("docker", "info").redirectErrorStream(true).start()
val dockerOk = dp.waitFor(10, java.util.concurrent.TimeUnit.SECONDS) && dp.exitValue() == 0
println("Docker: $dockerOk")
val changes = readAction {
    com.intellij.openapi.vcs.changes.ChangeListManager.getInstance(project)
        .allChanges.mapNotNull { it.virtualFile?.path }
}
println("VCS-modified files:\n" + changes.joinToString("\n"))
// Then read VCS-modified files + FAIL_TO_PASS test files in this SAME call or the next call.
// If dockerOk=false: still attempt to run FAIL_TO_PASS tests — many use H2 in-memory DB,
// no Docker needed. Only fall back to runInspectionsDirectly if the test fails with an
// explicit Docker connection error.
```

---

## Read a Project File
```kotlin
val text = VfsUtil.loadText(findProjectFile("src/main/resources/application.properties")!!)
println(text)
```

**⚠️ `findProjectFile()` pitfall for resource files**: requires the **FULL relative path** from the project root (e.g., `"src/main/resources/application.properties"`). Calling it with just a filename **always returns null** — causing NPE on `!!`. For files under `src/main/resources/`, use `FilenameIndex.getVirtualFilesByName()` which searches by filename:
```kotlin
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
val scope = GlobalSearchScope.projectScope(project)
val appProps = readAction {
    FilenameIndex.getVirtualFilesByName("application.properties", scope)
        .firstOrNull { it.path.contains("src/main/resources") }
} ?: error("application.properties not found in src/main/resources")
println(VfsUtil.loadText(appProps))
```

---

## ⚡ Read Multiple Files in One Call — PREFERRED Over Separate Calls (Saves ~20s Per Call)

> **⚠️ EXPLORATION RULE: Complete ALL exploration in AT MOST 2 exec_code calls.** (1) Test files + domain model in one batch. (2) Test infrastructure in a second batch only if needed. Do NOT issue one call per file group.
```kotlin
// Batch exploration: replace 5-8 sequential steroid_execute_code calls with 1
for (path in listOf(
    "pom.xml",
    "src/main/java/com/example/domain/CommentService.java",
    "src/main/java/com/example/domain/CommentRepository.java",
    "src/test/java/com/example/api/CommentControllerTest.java"
)) {
    val vf = findProjectFile(path) ?: run { println("NOT FOUND: $path"); continue }
    val content = VfsUtil.loadText(vf)
    // IMPORTANT: distinguish three states:
    //   NOT FOUND  → file doesn't exist at all (test_patch may add it later)
    //   EMPTY      → file exists but has no content (patch not yet applied, or placeholder)
    //   HAS_CONTENT → readable; process normally
    if (content.isEmpty()) { println("EMPTY (file exists but no content — may be a new file from test_patch not yet applied): $path"); continue }
    println("\n=== $path ===")
    println(content)
}
```

> **No redundant re-reads**: Files you read this session remain in your conversation history. Do NOT re-read them when switching task phases or `task_id`. Only re-read a file if you explicitly modified it.

---

## Find a File by Name — PREFERRED Over ProcessBuilder("find")
```kotlin
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope  // ← REQUIRED — missing this causes "unresolved reference"
// Find by exact filename — O(1) IDE index lookup, respects project scope
val matches = readAction {
    FilenameIndex.getVirtualFilesByName("UserServiceImpl.java", GlobalSearchScope.projectScope(project))
}
matches.forEach { println(it.path) }
// Find by extension + path filter:
val filtered = readAction {
    FilenameIndex.getAllFilesByExt(project, "java", GlobalSearchScope.projectScope(project))
        .filter { it.path.contains("user", ignoreCase = true) }
}
filtered.forEach { println(it.path) }
```

> **⚠️ Compile-error recovery**: If you get `unresolved reference 'GlobalSearchScope'`, add `import com.intellij.psi.search.GlobalSearchScope` and retry immediately. Do NOT abandon steroid_execute_code and fall back to Bash/grep after a compile error.

---

## Combined Discovery + Read in One Call

When you know target filenames from test imports — skip separate discovery step:
```kotlin
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.FilenameIndex
val targets = listOf(
    "UserServiceImpl.java", "UserRestControllerTests.java",
    "ExceptionControllerAdvice.java", "User.java"
)
val files = readAction {
    targets.flatMap {
        FilenameIndex.getVirtualFilesByName(it, GlobalSearchScope.projectScope(project)).toList()
    }
}
files.forEach { vf ->
    println("\n=== ${vf.name} (${vf.path}) ===")
    println(VfsUtil.loadText(vf))
}
```

---

## Search for Text Across Project Files — PREFERRED Over ProcessBuilder("grep")
```kotlin
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
// Find all Java files containing a literal string — uses IDE index, no regex pitfalls
import com.intellij.psi.search.PsiSearchHelper
val scope = GlobalSearchScope.projectScope(project)
val matchingFiles = mutableListOf<String>()
readAction {
    PsiSearchHelper.getInstance(project).processAllFilesWithWord("/api/", scope, { psiFile ->
        matchingFiles.add(psiFile.virtualFile.path)
        true  // continue searching
    }, true)
}
matchingFiles.forEach { println(it) }
// For broader substring search, filter by content after getting candidates:
val containing = readAction {
    FilenameIndex.getAllFilesByExt(project, "java", scope)
        .filter { vf -> VfsUtil.loadText(vf).contains("/api/v1") }
}
containing.forEach { println(it.path) }
```

---

## ⚠️ Multi-Agent Coordination — Check VCS Changes FIRST Before Writing Any Code
```kotlin
// Run this at the start of your task to detect files already created/modified by parallel agents
val changes = readAction {
    com.intellij.openapi.vcs.changes.ChangeListManager.getInstance(project)
        .allChanges.mapNotNull { it.virtualFile?.path }
}
println(if (changes.isEmpty()) "Clean slate — no prior agent changes" else "FILES ALREADY MODIFIED:\n" + changes.joinToString("\n"))
// If files are listed above: read them first before writing, to avoid overwriting work
```

**After VCS check: verify that changed files ACTUALLY solve the problem** (a prior agent may have created files in the WRONG package — modified files ≠ correct fix):
```kotlin
// Check whether required classes exist with correct FQN (not just any file)
val scope = com.intellij.psi.search.GlobalSearchScope.projectScope(project)
val required = listOf(
    "shop.api.core.product.Product",
    "shop.api.composite.product.ProductAggregate"
)
val missing = required.filter {
    readAction { com.intellij.psi.JavaPsiFacade.getInstance(project).findClass(it, scope) } == null
}
println(if (missing.isEmpty()) "All required classes present — run tests to verify"
        else "STILL MISSING (must create): " + missing.joinToString(", "))
```
