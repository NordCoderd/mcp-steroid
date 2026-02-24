
### Find Java/Kotlin Files via IDE Index (PREFERRED over shell find)

**Always prefer the IDE index over `ProcessBuilder("find", ...)`.** The IDE index respects source roots, handles not-yet-flushed writes, and stays consistent with PSI. Shell `find` bypasses indexing and may return stale or out-of-scope results.

```kotlin
// PREFERRED: IDE index — respects source roots, project scope
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope

val javaFiles = readAction {
    FilenameIndex.getAllFilesByExt(project, "java", GlobalSearchScope.projectScope(project))
}
println("Java files: ${javaFiles.size}")
javaFiles.forEach { vf -> println(vf.path) }

// Same for Kotlin:
val ktFiles = readAction {
    FilenameIndex.getAllFilesByExt(project, "kt", GlobalSearchScope.projectScope(project))
}
ktFiles.forEach { println(it.path) }

// Find a file by EXACT filename (fastest path — O(1) index lookup by name, no iteration)
val byName = readAction {
    FilenameIndex.getVirtualFilesByName("UserServiceImpl.java", GlobalSearchScope.projectScope(project))
}
byName.forEach { println(it.path) }

// Or by name + path substring filter (when multiple files have the same name):
val filtered = readAction {
    FilenameIndex.getAllFilesByExt(project, "java", GlobalSearchScope.projectScope(project))
        .filter { it.path.contains("user", ignoreCase = true) }
}
filtered.forEach { println(it.path) }

// AVOID: ProcessBuilder("find", "/mcp-run-dir/src", "-name", "*.java", "-type", "f")
// ↑ Bypasses IDE indexing — may miss newly-created files or include out-of-scope files
```

### Search for Text Across Project Files (PREFERRED Over shell grep/rg)

**Always prefer the IDE search API over `ProcessBuilder("grep", ...)` or `ProcessBuilder("rg", ...)`.**
Shell grep bypasses the IDE's PSI and may silently fail on regex escaping (`\/` is invalid in ripgrep).
`PsiSearchHelper` uses the same index as "Find in Files" — it's fast and always correct.

```kotlin
import com.intellij.psi.search.PsiSearchHelper
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.openapi.vfs.VfsUtil

// Option A: Find all files containing a specific word (word-boundary search)
// Use this for plain identifiers, class names, annotation names, etc.
val scope = GlobalSearchScope.projectScope(project)
val matchingFiles = mutableListOf<String>()
readAction {
    PsiSearchHelper.getInstance(project).processAllFilesWithWord("api", scope, { psiFile ->
        // Further filter if needed (e.g., only files that contain "/api/")
        if (psiFile.text.contains("/api/")) matchingFiles.add(psiFile.virtualFile.path)
        true  // return true to continue searching; false to stop early
    }, true)
}
matchingFiles.forEach { println(it) }

// Option B: Content filter over IDE-indexed files (for arbitrary substrings / URLs / paths)
// Faster than shell grep because it operates on the IDE's already-indexed file list
val filesWithPath = readAction {
    FilenameIndex.getAllFilesByExt(project, "java", GlobalSearchScope.projectScope(project))
        .filter { vf -> VfsUtil.loadText(vf).contains("/api/") }
}
filesWithPath.forEach { println(it.path) }

// Option C: Search in YAML/XML/properties files (no word boundary needed)
val yamlFiles = readAction {
    FilenameIndex.getAllFilesByExt(project, "yml", GlobalSearchScope.projectScope(project))
        .filter { vf -> VfsUtil.loadText(vf).contains("/api/") }
}
yamlFiles.forEach { println(it.path + ": " + VfsUtil.loadText(it).lines().filter { l -> l.contains("/api/") }.joinToString("; ")) }
```

### Combine Discovery + Batch Update in ONE Call (Eliminates Two-Step Pattern)

**Anti-pattern to avoid**: listing files first (call 1), then reading + updating each file (call 2 or more).
**Preferred pattern**: find files that match, read content, apply updates — all in a single exec_code call.

This approach eliminates the most common wasteful two-step pattern seen in Spring Boot refactoring tasks
(e.g., "update URL prefix in all controllers"). Instead of `FilenameIndex` → read each → decide → update,
do everything in one shot:

```kotlin
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.openapi.vfs.VfsUtil

// Single call: find all Java files containing "/api/v1" and replace with "/api/v2"
val scope = GlobalSearchScope.projectScope(project)
val toUpdate = readAction {
    FilenameIndex.getAllFilesByExt(project, "java", scope)
        .filter { vf -> VfsUtil.loadText(vf).contains("/api/v1") }
}
println("Files to update: ${toUpdate.size}")
toUpdate.forEach { vf ->
    val content = VfsUtil.loadText(vf)          // read OUTSIDE writeAction
    val updated = content.replace("/api/v1", "/api/v2")
    check(updated != content) { "Replace matched nothing in ${vf.name}" }
    writeAction { VfsUtil.saveText(vf, updated) } // write INSIDE writeAction
    println("Updated: ${vf.path}")
}
// Flush changes so git/shell tools see them immediately:
LocalFileSystem.getInstance().refresh(false)
println("Done — updated ${toUpdate.size} files")
```

> **Rule**: If you can describe your task as "find files with X, then update them" — do it in **one**
> exec_code call. Discovery + read + update in separate calls wastes ~20s per round-trip and provides
> no benefit since you're working with the same VFS state.

### Diagnosing String Replace Failures

If `check(updated != content)` fires with `"Replace matched nothing"`, the target string has slightly
different whitespace, indentation, or line endings than you expected — or a prior agent already modified
the file. **Always print the exact target region BEFORE attempting the replace:**

```kotlin
val vf = findProjectFile("src/main/java/com/example/ReleaseService.java")!!
val content = VfsUtil.loadText(vf)

// Step 1: Locate the target region and PRINT it before replacing (costs nothing; saves a retry turn):
val keyword = "updateRelease"   // keyword near your target
val idx = content.indexOf(keyword)
if (idx < 0) {
    println("KEYWORD_NOT_FOUND: '$keyword' — file may already be modified, or check the exact method name")
} else {
    println("EXCERPT (chars $idx..${idx + 300}):\n" + content.substring(idx, (idx + 300).coerceAtMost(content.length)))
}

// Step 2: Only AFTER confirming the exact text from the excerpt, perform the replace:
val updated = content.replace("exact old string from excerpt", "new string")
check(updated != content) { "No change — re-read the excerpt above and fix old_string" }
writeAction { VfsUtil.saveText(vf, updated) }
println("Updated: ${vf.name}")
```

**When a prior agent already modified the file**: The expected string may be gone or transformed.
Use `ChangeListManager.allChanges` to detect modified files, then re-read before replacing — do not
rely on a prior turn's view of the file content.

**Alternative when string replace fails repeatedly**: Use PSI surgery to add/modify fields and methods
directly (see "Add a Method to an Existing Java Class via PSI" below). PSI operations are whitespace-
insensitive and survive partial edits made by other agents.

### Batch Project Exploration (One Script Instead of Many Calls)

Explore the full project structure and read multiple relevant files in a single execution — avoid making 5+ separate calls to understand the codebase:

```kotlin
import com.intellij.openapi.roots.ProjectRootManager

// Step 1: Print the full file tree for src/main and src/test
// ⚠️ contentRoots accesses the project model — must be inside readAction { }
val contentRoots = readAction { ProjectRootManager.getInstance(project).contentRoots.toList() }
contentRoots.forEach { root ->
    VfsUtil.iterateChildrenRecursively(root, null) { file ->
        if (!file.isDirectory && (file.extension == "java" || file.extension == "kt" || file.name == "pom.xml")) {
            println(file.path.removePrefix(project.basePath!!))
        }
        true
    }
}
```

```kotlin
// Step 2: Read multiple files in a single script (batch instead of per-file calls)
val filesToRead = listOf(
    "src/main/java/com/example/domain/FeatureService.java",
    "src/main/java/com/example/api/controllers/FeatureController.java",
    "src/main/java/com/example/domain/models/Feature.java",
    "pom.xml"
)
for (path in filesToRead) {
    val vf = findProjectFile(path) ?: run { println("NOT FOUND: $path"); continue }
    println("\n=== $path ===")
    println(VfsUtil.loadText(vf))
}
```

### Semantic Code Navigation — Extract Structural Info Without Reading Full Files

**Prefer PSI-based structural queries over reading entire file contents.** When you need to know
"what methods does FeatureService have?" or "what fields does CommentDto have?", a single PSI call
answers that question in one round-trip — no need to read 5-6 full files one by one.

```kotlin
// Get all methods and fields of a Java class WITHOUT reading the full file text
val cls = readAction {
    JavaPsiFacade.getInstance(project).findClass(
        "com.sivalabs.ft.features.domain.FeatureService",
        GlobalSearchScope.projectScope(project)
    )
}
if (cls != null) {
    println("=== ${cls.qualifiedName} ===")
    println("Methods:")
    cls.methods.forEach { m ->
        val params = m.parameterList.parameters.joinToString { "${it.name}: ${it.type.presentableText}" }
        println("  ${m.name}($params): ${m.returnType?.presentableText}")
    }
    println("Fields:")
    cls.fields.forEach { f -> println("  ${f.name}: ${f.type.presentableText}") }
} else println("Class not found")
```

```kotlin
// Inspect multiple related classes in ONE script to understand codebase structure
val classesToInspect = listOf(
    "com.example.features.domain.FeatureRepository",
    "com.example.features.domain.CommentDto",
    "com.example.features.api.FeatureController"
)
for (fqn in classesToInspect) {
    val c = readAction { JavaPsiFacade.getInstance(project).findClass(fqn, projectScope()) }
    if (c == null) { println("NOT FOUND: $fqn"); continue }
    println("\n=== $fqn ===")
    c.methods.forEach { m -> println("  ${m.name}(${m.parameterList.parameters.size} params)") }
}
```

This replaces 6 separate `VfsUtil.loadText()` calls with 1 PSI-based structural query.

### Verify Project Package Structure Before Creating Files

**CRITICAL**: Always verify the actual package hierarchy via the IDE project model before creating new source files.
Directory names alone are NOT reliable — module source roots may start at a different depth than you expect.
Getting this wrong means your files are in the wrong package (tests pass locally but fail arena validation).

```kotlin
import com.intellij.openapi.roots.ProjectRootManager

// Step 1: List all content source roots (shows exactly where packages start)
// ⚠️ contentSourceRoots accesses the project model — must be inside readAction { }
readAction { ProjectRootManager.getInstance(project).contentSourceRoots.toList() }.forEach { root ->
    println("Source root: ${root.path}")
}

// Step 2: Check if a target package exists in the project model
val pkg = readAction { JavaPsiFacade.getInstance(project).findPackage("com.example.api") }
println("com.example.api exists: ${pkg != null}")

// Step 3: If the package is null, list top-level packages to find the correct root
val rootPkg = readAction { JavaPsiFacade.getInstance(project).findPackage("") }
rootPkg?.subPackages?.forEach { println("top-level package: ${it.qualifiedName}") }

// Step 4: Navigate the package hierarchy to find correct sub-packages
val apiPkg = readAction { JavaPsiFacade.getInstance(project).findPackage("com.example") }
apiPkg?.subPackages?.forEach { println("  sub-package: ${it.qualifiedName}") }
```

This prevents the common error of creating `com.example.microservices.api.Foo` when the project
actually uses `com.example.api.Foo` — a package mismatch that passes internal tests (JSON field matching)
but fails integration validation (class path matching).

**For empty modules with no existing source files** — also infer package convention from sibling modules:

```kotlin
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope

// When target module has no existing Java files (package can't be inferred locally),
// find existing packages in sibling modules to discover naming convention:
val allJavaFiles = readAction {
    FilenameIndex.getAllFilesByExt(project, "java", GlobalSearchScope.projectScope(project))
        .filter { it.path.contains("/main/java/") }
}
// Extract package names from existing files
val existingPackages = readAction {
    allJavaFiles.mapNotNull { vf ->
        val rel = vf.path.substringAfter("/main/java/")
        rel.substringBeforeLast("/").replace("/", ".")
    }.distinct().sorted()
}
println("Existing packages in sibling modules:")
existingPackages.take(10).forEach { println("  $it") }
// ↑ Use this to derive the correct base package (e.g. "shop.api" not "shop.microservices.api")
```

**⚠️ Pitfall: Gradle `group` ≠ Java package prefix**

The `group` field in `build.gradle` (`group = 'shop.microservices.api'`) is the **Maven artifact group coordinate** — it controls how the JAR is published to a repository. It does NOT determine the Java package hierarchy inside the source files. Projects commonly have:
- Gradle `group = 'shop.microservices.api'` (artifact coordinate)
- Actual Java package = `shop.api` (source code package)

**Always derive the required Java package from test import statements or existing source files — never from the Gradle `group` field.**

```kotlin
// Extract required packages from test imports (ground truth for new files):
import com.intellij.psi.PsiJavaFile
val testFile = readAction {
    FilenameIndex.getVirtualFilesByName("ProductServiceApiTests.java",
        GlobalSearchScope.projectScope(project)).firstOrNull()
}
val testImports = testFile?.let { vf -> readAction {
    (PsiManager.getInstance(project).findFile(vf) as? PsiJavaFile)
        ?.importList?.importStatements?.map { it.qualifiedName ?: "" }
} }
println("Packages to use for new files:\n" + testImports?.joinToString("\n"))
// ↑ e.g. "shop.api.core.product.Product" → create file in `shop/api/core/product/`
//   even if build.gradle says `group = 'shop.microservices.api'`
```
