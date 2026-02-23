/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.server

import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.openapi.application.ApplicationInfo
import com.intellij.openapi.application.readAction
import com.intellij.openapi.components.service
import com.intellij.openapi.extensions.PluginId
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager.getInstance
import com.intellij.openapi.util.registry.Registry
import com.jonnyzzz.mcpSteroid.execution.ExecutionManager
import com.jonnyzzz.mcpSteroid.storage.ExecutionId
import com.jonnyzzz.mcpSteroid.mcp.ContentItem
import com.jonnyzzz.mcpSteroid.mcp.McpServerCore
import com.jonnyzzz.mcpSteroid.mcp.ToolCallContext
import com.jonnyzzz.mcpSteroid.mcp.ToolCallResult
import com.jonnyzzz.mcpSteroid.updates.analyticsBeacon
import com.jonnyzzz.mcpSteroid.prompts.generated.skill.CodingWithIntelliJPromptArticle
import com.jonnyzzz.mcpSteroid.prompts.generated.skill.DebuggerSkillPromptArticle
import com.jonnyzzz.mcpSteroid.prompts.generated.skill.SkillPromptArticle
import com.jonnyzzz.mcpSteroid.prompts.generated.skill.TestSkillPromptArticle
import kotlinx.serialization.json.*

data class ExecCodeParams(
    val taskId: String,
    val code: String,
    val reason: String,
    val timeout: Int,
    //TODO: move that away from here, allow changes only via the McpScriptContext::doNotCancelOnModalityStateChange
    /** If true, cancel execution when a modal dialog appears and return a screenshot. Default true. */
    val cancelOnModal: Boolean = true,

    /** Controls pre-execution dialog killer: null = use registry default, true = force enable, false = force disable. */
    val dialogKiller: Boolean? = null,

    val rawParams: JsonObject,
)

/**
 * Handler for the steroid_execute_code MCP tool.
 */
class ExecuteCodeToolHandler : McpRegistrar {
    private val toolDescription get() = run {
        val codingGuideUri = CodingWithIntelliJPromptArticle().uri
        val skillUri = SkillPromptArticle().uri
        val debuggerUri = DebuggerSkillPromptArticle().uri
        val testUri = TestSkillPromptArticle().uri
        """
             WHAT: Finally SEE IntelliJ-based IDEs - not just read code. The only MCP server with visual understanding and full IDE control.
             HOW: Execute Kotlin code directly in IntelliJ's runtime with full API access.

             📖 **COMPLETE GUIDE**: [Coding with IntelliJ APIs]($codingGuideUri)

             This is a **stateful** API - everything you do changes the IDE state. The IntelliJ IDE is running exclusively for you. Use it aggressively instead of manual file operations.

             **⚡ Bypasses Agent Sandbox**: Scripts run inside IntelliJ's JVM — unrestricted filesystem access. Use steroid_execute_code to read/write project files INSTEAD of agent-side file tools (those are sandboxed to /home/agent and cannot access /mcp-run-dir/ or the project). Do NOT use shell heredocs for multi-line file creation — use VFS APIs below.

             **EXCEPTION — use native Read tool for simple file reads**: The native Read tool can access /mcp-run-dir/ paths directly. For reading a single file's content, prefer the Read tool over VfsUtil.loadText() — it's faster (no compilation overhead). Reserve exec_code for operations that REQUIRE IntelliJ APIs: PSI analysis, compilation checks, test execution, find usages, refactoring, VCS inspection. If you just need to read file text, use the Read tool.

             **⚠️ exec_code VFS reads do NOT satisfy the native Edit tool's read-before-write constraint**: If you read a file via exec_code (`VfsUtil.loadText(vf)`) and then try to use the native `Edit` tool on the same file, you will get `"File has not been read yet"`. These are tracked separately. Options: (a) also issue a native `Read` tool call for that file before using `Edit`, or (b) use a `writeAction { }` block in exec_code to both read and write the file atomically — this is PREFERRED because it saves a round-trip and avoids the constraint entirely:
             ```kotlin
             val vf = findProjectFile("src/main/java/com/example/MyClass.java")!!
             val content = VfsUtil.loadText(vf)  // read OUTSIDE writeAction
             val updated = content.replace("oldMethod", "newMethod")
             check(updated != content) { "replace matched nothing — check whitespace" }
             writeAction { VfsUtil.saveText(vf, updated) }  // write INSIDE writeAction
             // ↑ This replaces both Read + Edit tools in a single exec_code call
             ```

             **Quick Start:**
             - Your code is a suspend function body (never use runBlocking)
             - Use readAction { } for PSI/VFS reads, writeAction { } for modifications
             - waitForSmartMode() runs automatically before your script
             - Available: project, println(), printJson(), printException(), progress()
             - **Helper functions that call readAction/writeAction MUST be `suspend fun`** — a regular `fun` that calls these gets a compile error: `"suspension functions can only be called within coroutine body"`. This applies to ALL suspend-context APIs: `readAction`, `writeAction`, `smartReadAction`, `waitForSmartMode`, `runInspectionsDirectly`.

             **⚠️ THREADING RULE — NEVER SKIP**: Any PSI access (JavaPsiFacade, PsiShortNamesCache, PsiManager.findFile, `ProjectRootManager.contentSourceRoots`, module roots, annotations, etc.) **MUST** be wrapped in `readAction { }`. Modifications require `writeAction { }`. Threading violations throw immediately at runtime — they are not silently ignored. **This applies to ALL PSI calls including your very first exploration call** (e.g. listing source roots). This is the most common first-attempt error.

             **⚠️ Do NOT install plugins or modify IDE internals via reflection**: This environment is pre-configured. If `required_plugins` fails, report the missing plugin ID — do NOT attempt `PluginsAdvertiser`, `PluginInstaller`, `PluginManagerCore` write APIs, or any reflection-based installation. These throw `IllegalArgumentException` / `IllegalAccessError` at runtime and waste a turn.

             **⚠️ writeAction { } is NOT a coroutine scope**: Calling `readAction { }` or ANY suspend function inside `writeAction { }` throws `suspension functions can only be called within coroutine body`. ALWAYS read first (outside), then write (inside):
             ```kotlin
             val vf = findProjectFile("src/main/java/com/example/Foo.java")!!
             val content = VfsUtil.loadText(vf)               // read OUTSIDE writeAction
             // ⚠️ BEFORE content.replace() — print the exact target slice to verify whitespace:
             // Silent no-match (wrong whitespace, extra blank line after `{`) returns the original
             // unchanged and wastes a full retry turn with zero feedback. Always verify first:
             val idx = content.indexOf("methodName")
             println("EXCERPT:\n" + content.substring(idx, (idx + 250).coerceAtMost(content.length)))
             // Only then do the replace, verifying the result is different from content:
             val updated = content.replace("oldString", "newString")
             check(updated != content) { "content.replace matched nothing — whitespace mismatch!" }
             writeAction { VfsUtil.saveText(vf, updated) }    // write INSIDE — no suspend calls allowed
             // After bulk VFS edits, flush to disk before running git/shell subprocesses:
             LocalFileSystem.getInstance().refresh(false)     // ensures git diff sees the changes
             ```
             Or use `edtWriteAction { }` (a suspend wrapper) if you need suspend calls inside the write block.

             **⚠️ ALL VFS mutation ops need writeAction — not just saveText**: `createDirectoryIfMissing()`, `createChildData()`, `createChildFile()`, `createChildDirectory()`, `delete()`, `rename()`, `move()`, and `saveText()` ALL require writeAction. Calling any of these OUTSIDE a writeAction throws `Write access is allowed inside write-action only` at runtime. Always put the ENTIRE create-directory-and-write sequence inside a SINGLE writeAction block:
             ```kotlin
             writeAction {
                 val root = LocalFileSystem.getInstance().findFileByPath(project.basePath!!)!!
                 val dir = VfsUtil.createDirectoryIfMissing(root, "src/main/java/com/example/model")  // ← needs writeAction
                 val f = dir.findChild("Product.java") ?: dir.createChildData(this, "Product.java")   // ← needs writeAction
                 VfsUtil.saveText(f, content)                                                          // ← needs writeAction
             }
             // ✗ WRONG: val dir = VfsUtil.createDirectoryIfMissing(...) OUTSIDE writeAction, then writeAction { saveText }
             // ↑ This throws "Write access is allowed inside write-action only" on the createDirectoryIfMissing call
             ```

             **⚡ First-call readiness probe (verify IDE + MCP connectivity before heavy ops):**
             ```kotlin
             println("IDE ready: ${'$'}{project.name}")
             println("Base path: ${'$'}{project.basePath}")
             println("Smart mode: ${'$'}{!com.intellij.openapi.project.DumbService.isDumb(project)}")
             ```
             > **Once smart mode is confirmed, do NOT re-probe before each subsequent operation.** Combine the readiness check with your first real action to save round-trips (~20s each). Only re-probe if you triggered a Maven import or other index-invalidating step.

             **⚡ PSI Structural Query — explore class structure WITHOUT reading files (replaces 5-10 VfsUtil.loadText calls):**
             ```kotlin
             // When you need to know a class's methods, fields, or call-sites — use PSI.
             // This 1 call replaces reading 5-10 separate files just to trace code flow.
             val cls = readAction {
                 JavaPsiFacade.getInstance(project).findClass(
                     "com.example.domain.FeatureService",   // ← actual FQN from pom.xml group + class name
                     GlobalSearchScope.projectScope(project)
                 )
             }
             // Print all methods (no file read needed):
             cls?.methods?.forEach { m ->
                 val params = m.parameterList.parameters.joinToString { "${'$'}{it.name}: ${'$'}{it.type.presentableText}" }
                 println("${'$'}{m.name}(${'$'}params): ${'$'}{m.returnType?.presentableText}")
             }
             // Find ALL callers/usages (replaces grepping through source files):
             import com.intellij.psi.search.searches.ReferencesSearch
             ReferencesSearch.search(cls!!, projectScope()).findAll().forEach { ref ->
                 val snippet = ref.element.parent.text.take(80)
                 println("${'$'}{ref.element.containingFile.name} → ${'$'}snippet")
             }
             ```
             > **Rule**: If you're about to read a 3rd file just to trace code flow, use `ReferencesSearch.search()` or `JavaPsiFacade.findClass()` instead. PSI answers in 1 call what file reading needs 5-10 calls to reconstruct.

             **Read a project file:**
             ```kotlin
             val text = VfsUtil.loadText(findProjectFile("src/main/resources/application.properties")!!)
             println(text)
             ```

             **Read multiple files in one call — PREFERRED over separate calls (saves ~20s per call):**
             ```kotlin
             // Batch exploration: replace 5-8 sequential steroid_execute_code calls with 1
             // Read pom.xml + key source files + failing test in a single call
             for (path in listOf(
                 "pom.xml",
                 "src/main/java/com/example/domain/CommentService.java",
                 "src/main/java/com/example/domain/CommentRepository.java",
                 "src/test/java/com/example/api/CommentControllerTest.java"
             )) {
                 val vf = findProjectFile(path) ?: run { println("NOT FOUND: ${'$'}path"); continue }
                 println("\n=== ${'$'}path ===")
                 println(VfsUtil.loadText(vf))
             }
             ```
             > **No redundant re-reads**: Files you read this session remain in your conversation history. Do NOT re-read them when switching task phases or `task_id`. Only re-read a file if you explicitly modified it. Re-reading already-seen files wastes ~20s per call and provides zero new information.

             **Create/write a Java or Kotlin source file:**
             ```kotlin
             writeAction {
                 // DEPRECATED: project.baseDir — use LocalFileSystem instead:
                 val root = LocalFileSystem.getInstance().findFileByPath(project.basePath!!)!!
                 val dir = VfsUtil.createDirectoryIfMissing(root, "src/main/java/com/example/model")
                 val f = dir.findChild("Product.java") ?: dir.createChildData(this, "Product.java")
                 // Use joinToString() or File.writeText() — NOT a triple-quoted string with 'import' at line start
                 // (the preprocessor extracts import-like lines from triple-quoted strings as Kotlin imports)
                 // ⚠️ VfsUtil.saveText() REPLACES THE ENTIRE FILE — for adding a single method to an existing
                 // class, use PSI writeCommandAction + factory.createMethodFromText() instead (see guide).
                 VfsUtil.saveText(f, listOf(
                     "package com.example.model;",
                     "import" + " jakarta.persistence.Entity;",
                     "import" + " jakarta.persistence.Id;",
                     "@Entity public class Product { @Id private Long id; }"
                 ).joinToString("\n"))
             }
             println("File created")
             ```

             **⚠️ Import-in-strings pitfall**: Never put `import foo.Bar;` at the start of a line inside a triple-quoted Kotlin string. The script preprocessor extracts those lines as Kotlin imports, causing compile errors. Use `"import" + " foo.Bar;"` or `joinToString` to build the content, or use `java.io.File(path).writeText(content)` as an alternative.

             **⚠️ Char-literal pitfall in string-assembled Java**: When building Java source via Kotlin `joinToString()`, char literals like `'\''` cause escaping errors — the Kotlin string `"'\\''"` produces Java text `'\''` which is a Java syntax error (empty char literal). For `toString()` methods or any code with char literals, use triple-quoted Kotlin strings with `java.io.File.writeText()` (not affected by the import extractor), or use `PsiFileFactory.createFileFromText()` for complex Java bodies — it handles all escaping automatically. After writing, verify with `check(VfsUtil.loadText(f).contains("class Product")) { "Write failed" }`.

             **Find a file by name (PREFERRED over ProcessBuilder("find"), java.io.File.walkTopDown(), or shell):**
             ```kotlin
             import com.intellij.psi.search.FilenameIndex
             import com.intellij.psi.search.GlobalSearchScope  // ← REQUIRED — not auto-imported; missing this causes "unresolved reference 'GlobalSearchScope'"
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
             > **⚠️ Compile-error recovery**: If you get `unresolved reference 'GlobalSearchScope'`, add `import com.intellij.psi.search.GlobalSearchScope` and retry immediately. Do NOT abandon steroid_execute_code and fall back to Bash/grep after a compile error — one corrected steroid call is faster than 10 grep commands. Read the error message, fix the import, resubmit.
             **Combined discovery + read in ONE call** (when you know target filenames from test imports — skip separate discovery step):
             ```kotlin
             import com.intellij.psi.search.FilenameIndex
             // Discover file paths AND read content in a single steroid_execute_code call (~20s saved per extra call)
             // Use this when test imports reveal class names (e.g. UserServiceImpl, UserRestControllerTests)
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
                 println("\n=== ${'$'}{vf.name} (${'$'}{vf.path}) ===")
                 println(VfsUtil.loadText(vf))
             }
             // Tip: If no files found, check if names match exactly (case-sensitive). List all Java files:
             // readAction { FilenameIndex.getAllFilesByExt(project, "java", GlobalSearchScope.projectScope(project)) }
             //     .filter { it.name.contains("service", ignoreCase = true) }.forEach { println(it.name) }
             ```
             **Search for text across project files (PREFERRED over ProcessBuilder("grep") or ProcessBuilder("rg")):**
             ```kotlin
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

             **⚠️ Multi-agent coordination — check VCS changes FIRST before writing any code** (another agent slot may have already created or modified files in this shared project):
             ```kotlin
             // Run this at the start of your task to detect files already created/modified by parallel agents
             val changes = readAction {
                 com.intellij.openapi.vcs.changes.ChangeListManager.getInstance(project)
                     .allChanges.mapNotNull { it.virtualFile?.path }
             }
             println(if (changes.isEmpty()) "Clean slate — no prior agent changes" else "FILES ALREADY MODIFIED:\n" + changes.joinToString("\n"))
             // If files are listed above: read them first before writing, to avoid overwriting work
             ```
             **⚠️ After VCS check: verify that changed files ACTUALLY solve the problem** (a prior agent may have created files in the WRONG package — modified files ≠ correct fix):
             ```kotlin
             // Step 2: Check whether required classes exist with correct FQN (not just any file)
             // Replace the list below with the classes your task requires
             val scope = com.intellij.psi.search.GlobalSearchScope.projectScope(project)
             val required = listOf(
                 "shop.api.core.product.Product",        // ← replace with actual required FQNs
                 "shop.api.composite.product.ProductAggregate"
             )
             val missing = required.filter {
                 readAction { com.intellij.psi.JavaPsiFacade.getInstance(project).findClass(it, scope) } == null
             }
             println(if (missing.isEmpty()) "All required classes present — run tests to verify"
                     else "STILL MISSING (must create): " + missing.joinToString(", "))
             // ↑ Only skip re-implementation if ALL required classes resolve correctly via PSI
             ```

             **⚠️ NO AUTO-IMPORTS — every IntelliJ class must be imported explicitly.** A missing import produces `unresolved reference` (sometimes misleadingly as a type-inference error) and wastes a full retry turn. Common imports not auto-added by the preprocessor:
             ```kotlin
             import com.intellij.psi.search.FilenameIndex        // getVirtualFilesByName, getAllFilesByExt
             import com.intellij.psi.search.GlobalSearchScope    // projectScope(), allScope()
             import com.intellij.openapi.roots.ProjectRootManager // contentSourceRoots
             import com.intellij.openapi.vfs.VfsUtil              // loadText(), saveText(), createDirectoryIfMissing()
             import com.intellij.psi.search.PsiShortNamesCache   // allClassNames
             import com.intellij.psi.search.searches.AnnotatedElementsSearch  // find @Entity/@Service classes
             import com.intellij.psi.search.searches.ReferencesSearch         // find all usages of a class
             ```

             **Spring Boot / Maven patterns:**
             ```kotlin
             // ⚠️ ALWAYS verify package structure BEFORE creating new files (do NOT guess from directory names)
             // Step 1: List all content source roots to understand the module layout
             // ⚠️ contentSourceRoots accesses the project model — MUST be inside readAction { }
             import com.intellij.openapi.roots.ProjectRootManager
             readAction { ProjectRootManager.getInstance(project).contentSourceRoots }.forEach { println(it.path) }
             // Step 2: Check if the target package actually exists in the project model
             val pkg = readAction { JavaPsiFacade.getInstance(project).findPackage("shop.api.core") }
             println("shop.api.core exists: ${'$'}{pkg != null}")
             // If the package doesn't exist, list top-level packages to find the real one:
             val topPkg = readAction { JavaPsiFacade.getInstance(project).findPackage("") }
             topPkg?.subPackages?.forEach { println(it.qualifiedName) }
             ```
             ```kotlin
             // ⚠️ CRITICAL: Derive required package names from TEST IMPORT STATEMENTS — not from Gradle group ID.
             // The Gradle `group = 'shop.microservices.api'` in build.gradle is a Maven artifact coordinate,
             // NOT the Java package prefix. These often differ! Always read actual test imports first.
             // Example: a test with `import shop.api.core.product.Product;` tells you the package is
             // `shop.api.core.product` — even if the Gradle group is `shop.microservices.api`.
             import com.intellij.psi.PsiJavaFile
             val testFile = readAction {
                 FilenameIndex.getVirtualFilesByName("ProductServiceApiTests.java",
                     GlobalSearchScope.projectScope(project)).firstOrNull()
             }
             val testImports = testFile?.let { vf -> readAction {
                 (PsiManager.getInstance(project).findFile(vf) as? PsiJavaFile)
                     ?.importList?.importStatements?.map { it.qualifiedName ?: "" }
             }}
             println("Test imports (use these packages for new files):\n" + testImports?.joinToString("\n"))
             // ↑ The package prefixes from these imports are the GROUND TRUTH for any new files to create.
             // Do NOT use the Gradle `group` field as the package base — it is NOT the Java package.
             ```
             ```kotlin
             // Find all @Entity classes in the project
             import com.intellij.psi.search.searches.AnnotatedElementsSearch
             val entityAnnotation = readAction {
                 JavaPsiFacade.getInstance(project).findClass("jakarta.persistence.Entity", allScope())
             }
             AnnotatedElementsSearch.searchPsiClasses(entityAnnotation!!, projectScope()).findAll()
                 .forEach { println(it.qualifiedName) }
             ```
             ```kotlin
             // Trigger Maven re-import after editing pom.xml
             import org.jetbrains.idea.maven.project.MavenProjectsManager
             MavenProjectsManager.getInstance(project).forceUpdateAllProjectsOrFindAllAvailablePomFiles()
             println("Maven sync triggered")
             ```
             ```kotlin
             // Check for compile errors in a Java file (faster than running mvn test)
             val vf = findProjectFile("src/main/java/com/example/Product.java")!!
             val problems = runInspectionsDirectly(vf)
             problems.forEach { (id, descs) -> descs.forEach { println("[" + id + "] " + it.descriptionTemplate) } }
             ```
             ```kotlin
             // PREFERRED: compile-check multiple new files at once — ~5s vs ~90s for ./mvnw test-compile
             // Run this INSTEAD of ./mvnw test-compile after creating new files
             for (path in listOf(
                 "src/main/java/com/example/FeatureReactionService.java",
                 "src/main/java/com/example/FeatureReactionController.java",
                 "src/main/java/com/example/AddReactionPayload.java"
             )) {
                 val vf = findProjectFile(path) ?: run { println("NOT FOUND: ${'$'}path"); continue }
                 val problems = runInspectionsDirectly(vf)
                 if (problems.isEmpty()) println("OK: ${'$'}path")
                 else problems.forEach { (id, d) -> d.forEach { println("[${'$'}id] ${'$'}{it.descriptionTemplate}") } }
             }
             ```
             ⚠️ **`./mvnw test-compile` takes ~90s per Maven cold-start. Use `runInspectionsDirectly` (above, ~5s) for compile checks. Reserve `./mvnw` for final full test execution only.**
             ```kotlin
             // ⚠️ AFTER EDITING pom.xml → use ProcessBuilder("./mvnw") as PRIMARY (not the IDE runner).
             //   After pom.xml changes, IntelliJ triggers a Maven project re-import that shows a modal
             //   dialog, blocking the IDE runner latch for up to 600s. ProcessBuilder bypasses this.
             //   Example pattern: edit pom.xml → writeAction { VfsUtil.saveText(...) } → ProcessBuilder below.
             // ⚠️ When pom.xml was NOT modified, prefer the Maven IDE runner from the skill guide
             //   (mcp-steroid://skill/coding-with-intellij, "Run Tests via IntelliJ IDE Runner → Maven").
             //   It avoids 200k-char truncation and gives structured pass/fail results.
             //   When using the IDE runner: always pass dialog_killer: true to auto-dismiss any modals.
             // ⚠️ Always use ./mvnw (Maven wrapper) not 'mvn' — system mvn is not installed
             // ⚠️ CRITICAL: Spring Boot test output routinely exceeds 200k chars (startup logs + Flyway
             //    migration output + Testcontainers + stack traces). NEVER print untruncated output —
             //    it causes MCP token limit errors. Always use takeLast() to capture only what matters:
             // ⚠️ Do NOT use -q — Maven quiet mode suppresses [INFO] output including "Tests run:" summary.
             //    Exit code 0 alone is NOT sufficient to confirm test results. Use takeLast() for truncation instead.
             val process = ProcessBuilder("./mvnw", "test", "-Dtest=MyValidatorTest", "-Dspotless.check.skip=true")
                 .directory(java.io.File(project.basePath!!))
                 .redirectErrorStream(true).start()
             val lines = process.inputStream.bufferedReader().readLines()
             val exitCode = process.waitFor()
             println("Exit: ${'$'}exitCode | total output lines: ${'$'}{lines.size}")
             // ⚠️ Capture BOTH ends: Spring context / Testcontainers failures appear at the START;
             // Maven BUILD FAILURE summary appears at the END. takeLast alone misses early errors.
             println("--- First 30 lines (Spring context / Testcontainers errors appear here) ---")
             println(lines.take(30).joinToString("\n"))
             println("--- Last 30 lines (Maven BUILD FAILURE summary appears here) ---")
             println(lines.takeLast(30).joinToString("\n"))
             ```
             ⚠️ **Run FAIL_TO_PASS tests one at a time** — NOT `-Dtest=Test1,Test2,Test3,Test4` all at once.
             4 Spring Boot tests × 25k chars each = 100k+ chars → MCP token overflow → multi-step Bash recovery.
             Run each individually first. Only batch after confirming each passes.
             ⚠️ **When take/takeLast is still not enough** (multiple tests, verbose output), use keyword filtering:
             ```kotlin
             // Keyword-filtered Maven output — prevents token overflow from verbose Spring Boot/Testcontainers logs
             // ⚠️ Do NOT use -q — Maven quiet mode suppresses all [INFO] lines including "Tests run:" summary;
             //    exit code 0 alone is NOT sufficient to confirm tests passed. Use keyword filter instead.
             val proc = ProcessBuilder("./mvnw", "test", "-Dtest=OnlyOneTestClass", "-Dspotless.check.skip=true")
                 .directory(java.io.File(project.basePath!!)).redirectErrorStream(true).start()
             val lines = proc.inputStream.bufferedReader().readLines()
             val done = proc.waitFor(180, java.util.concurrent.TimeUnit.SECONDS)
             val keywords = listOf("Tests run:", "FAILED", "ERROR", "Caused by:", "BUILD", "Could not", "Exception in")
             println("Exit: ${'$'}{if (done) proc.exitValue() else "TIMEOUT"} | lines: ${'$'}{lines.size}")
             // ⚠️ Use println(joinToString) — NOT forEach(::println). forEach may not flush all lines as MCP stream events.
             println(lines.take(20).joinToString("\n"))   // Spring startup errors at top
             println(lines.filter { l -> keywords.any { k -> k in l } }.take(50).joinToString("\n"))  // signal lines
             println(lines.takeLast(15).joinToString("\n")) // Maven BUILD FAILURE at bottom
             ```
             ```kotlin
             // Docker pre-check BEFORE running @Testcontainers tests — fail fast, save 2-3 turns
             // Run this FIRST when you suspect Docker may be unavailable in the environment
             val dp = ProcessBuilder("docker", "info").redirectErrorStream(true).start()
             val dockerOk = dp.waitFor(10, java.util.concurrent.TimeUnit.SECONDS) && dp.exitValue() == 0
             println("Docker available: ${'$'}dockerOk")
             // If dockerOk=false: use runInspectionsDirectly for verification, skip integration tests
             ```
             ```kotlin
             // Run a specific JUnit test class via IntelliJ runner (NON-MAVEN/GRADLE PROJECTS ONLY)
             // ⚠️ For Maven projects: use MavenRunConfigurationType (skill guide) — JUnitConfiguration does NOT resolve Maven deps and WILL fail for Spring Boot tests
             // ⚠️ For Gradle projects: use GradleRunConfiguration.setRunAsTest(true) (skill guide)
             import com.intellij.execution.junit.JUnitConfiguration
             import com.intellij.execution.junit.JUnitConfigurationType
             import com.intellij.execution.RunManager
             import com.intellij.execution.ProgramRunnerUtil
             import com.intellij.execution.executors.DefaultRunExecutor
             val factory = JUnitConfigurationType.getInstance().configurationFactories.first()
             val config = factory.createConfiguration("Run test", project) as JUnitConfiguration
             val data = config.persistentData               // typed as JUnitConfiguration.Data
             data.TEST_CLASS = "com.example.MyTest"
             data.TEST_OBJECT = JUnitConfiguration.TEST_CLASS  // ← constant, NOT string "class"
             config.setWorkingDirectory(project.basePath!!)
             val settings = RunManager.getInstance(project).createConfiguration(config, factory)
             RunManager.getInstance(project).addConfiguration(settings)
             ProgramRunnerUtil.executeConfiguration(settings, DefaultRunExecutor.getRunExecutorInstance())
             println("Test run started")
             // ⚠️ Pitfall: `data.TEST_OBJECT = "class"` → compile error "unresolved reference 'TEST_CLASS'"
             // Always use the constant: JUnitConfiguration.TEST_CLASS
             ```
             ```kotlin
             // ⚠️ Anti-patterns: avoid these in steroid_execute_code scripts:
             // - NEVER use ProcessBuilder("./gradlew") inside exec_code for test execution.
             //   This spawns a nested Gradle daemon from within the IDE JVM → classpath conflicts.
             //   Use ExternalSystemUtil.runTask (PREFERRED) or the Bash tool (FALLBACK).
             // - NEVER use java.io.File.walkTopDown() or ProcessBuilder("find") for directory listing.
             //   These bypass IntelliJ VFS entirely — no module boundary awareness, no source root filtering.
             //   PREFERRED: FilenameIndex.getAllFilesByExt() or VfsUtil.collectChildrenRecursively()
             //   which respect VFS state and module scope.
             import com.intellij.openapi.externalSystem.model.execution.ExternalSystemTaskExecutionSettings
             import com.intellij.openapi.externalSystem.service.execution.ProgressExecutionMode
             import com.intellij.openapi.externalSystem.task.TaskCallback
             import com.intellij.openapi.externalSystem.util.ExternalSystemUtil
             import org.jetbrains.plugins.gradle.util.GradleConstants
             import kotlinx.coroutines.CompletableDeferred
             import kotlinx.coroutines.withTimeout
             import kotlin.time.Duration.Companion.minutes
             val result = CompletableDeferred<Boolean>()
             val s = com.intellij.openapi.externalSystem.model.execution.ExternalSystemTaskExecutionSettings()
             s.externalProjectPath = project.basePath!!
             s.taskNames = listOf(":api:test", "--tests", "shop.api.composite.product.ProductCompositeServiceApplicationTests")
             s.externalSystemIdString = GradleConstants.SYSTEM_ID.toString()
             ExternalSystemUtil.runTask(s, com.intellij.execution.executors.DefaultRunExecutor.EXECUTOR_ID,
                 project, GradleConstants.SYSTEM_ID,
                 object : TaskCallback { override fun onSuccess() { result.complete(true) }; override fun onFailure() { result.complete(false) } },
                 ProgressExecutionMode.IN_BACKGROUND_ASYNC, false)
             val ok = withTimeout(5.minutes) { result.await() }; println("Gradle result: success=${'$'}ok")
             // ⚠️ If success=false: do NOT immediately fall back to ProcessBuilder("./gradlew").
             // ⚠️ CRITICAL: Even if ProcessBuilder("./gradlew") exits 0 with "BUILD SUCCESSFUL",
             //    that only means Gradle completed WITHOUT error — NOT that tests ran and passed.
             //    Gradle may have compiled the code successfully but skipped the test phase entirely
             //    (e.g. UP-TO-DATE tasks, or a compilation error stopping before tests run).
             //    ONLY `Tests run: X, Failures: 0, Errors: 0` in the output proves tests actually ran.
             // When success=false: read JUnit XML test results directly for failure details:
             val testResultsDir = findProjectFile("build/test-results/test")
             testResultsDir?.children?.filter { it.name.endsWith(".xml") }?.forEach { xmlFile ->
                 val content = com.intellij.openapi.vfs.VfsUtil.loadText(xmlFile)
                 val failures = Regex("<failure[^>]*>(.+?)</failure>", RegexOption.DOT_MATCHES_ALL)
                     .findAll(content).map { it.groupValues[1].take(300) }.toList()
                 if (failures.isNotEmpty()) println("FAIL ${'$'}{xmlFile.name}: " + failures.first())
                 else println("PASS ${'$'}{xmlFile.name}")
             }
             ```
             ```kotlin
             // ClassCanBeRecord inspection on a newly created DTO = ALWAYS convert to Java record.
             // This is NOT optional style feedback — new DTO/data classes MUST be records when the
             // inspection says so (reference solutions use records). Ignoring it causes structural mismatch.
             // WRONG: class ProductAggregate { String name; int weight; }  ← creates ClassCanBeRecord warning
             // CORRECT (record from the start):
             writeAction {
                 val root = com.intellij.openapi.vfs.LocalFileSystem.getInstance().findFileByPath(project.basePath!!)!!
                 val dir = com.intellij.openapi.vfs.VfsUtil.createDirectoryIfMissing(root, "src/main/java/shop/api/core/product")
                 val f = dir.findChild("Product.java") ?: dir.createChildData(this, "Product.java")
                 com.intellij.openapi.vfs.VfsUtil.saveText(f, listOf(
                     "package shop.api.core.product;",
                     "",
                     "public record Product(int productId, String name, int weight) {}"
                 ).joinToString("\n"))
             }
             // Verify: runInspectionsDirectly should return NO ClassCanBeRecord after using record syntax
             ```
             ```kotlin
             // After bulk file creation, verify what was actually created (prevent duplicate calls)
             import com.intellij.psi.search.FilenameIndex
             val created = readAction {
                 FilenameIndex.getAllFilesByExt(project, "java", com.intellij.psi.search.GlobalSearchScope.projectScope(project))
                     .filter { it.path.contains("/src/main/java/") }
                     .map { it.name + " @ " + it.path.substringAfter(project.basePath!!) }
             }
             println("Created Java files:\n" + created.joinToString("\n"))
             // If a file you expected is missing, create ONLY that one — do not recreate the others
             ```
             ```kotlin
             // Check if a Java class already exists before creating it (prevent duplicate files)
             val existing = readAction {
                 com.intellij.psi.JavaPsiFacade.getInstance(project).findClass(
                     "com.example.MyClass",
                     com.intellij.psi.search.GlobalSearchScope.projectScope(project)
                 )
             }
             println(if (existing == null) "NOT_FOUND: safe to create" else "EXISTS: " + existing.containingFile.virtualFile.path)
             ```
             ```kotlin
             // Discover existing class naming conventions BEFORE creating new classes
             // (avoids naming mismatches like CreateCommentPayload vs AddReplyPayload vs CreateReplyPayload)
             // Always do this FIRST when the test doesn't import the payload class directly.
             import com.intellij.psi.search.PsiShortNamesCache
             val allNames = readAction { PsiShortNamesCache.getInstance(project).allClassNames.toList() }
             allNames.filter { it.endsWith("Payload") || it.endsWith("Request") || it.endsWith("Dto") ||
                 it.endsWith("Status") || it.endsWith("Type") || it.endsWith("Service") }
                 .sorted().forEach { println(it) }
             ```
             ```kotlin
             // Find next Flyway migration version number (avoids V5__ collision if V5__ already exists)
             val migDir = findProjectFile("src/main/resources/db/migration")!!
             val nextVersion = readAction {
                 migDir.children.map { it.name }
                     .mapNotNull { Regex("V(\\d+)__").find(it)?.groupValues?.get(1)?.toIntOrNull() }
                     .maxOrNull()?.plus(1) ?: 1
             }
             println("NEXT_MIGRATION=V" + nextVersion)
             ```
             ```kotlin
             // Find all usages of a class (call sites, constructor invocations)
             // CRITICAL when adding new fields to command/DTO objects — find every call site first
             // so you can update all constructors before running the compiler
             import com.intellij.psi.search.searches.ReferencesSearch
             val cmdClass = readAction {
                 JavaPsiFacade.getInstance(project).findClass("com.example.CreateReleaseCommand", projectScope())
             }
             if (cmdClass != null) {
                 ReferencesSearch.search(cmdClass, projectScope()).findAll().forEach { ref ->
                     val file = ref.element.containingFile.virtualFile.path.substringAfterLast('/')
                     println("${'$'}file → " + ref.element.parent.text.take(100))
                 }
             } else println("class not found")
             ```
             ```kotlin
             // Find @Repository methods with @Query annotations (understand existing DB patterns)
             val repo = readAction {
                 JavaPsiFacade.getInstance(project).findClass("com.example.ReleaseRepository", projectScope())
             }
             repo?.methods?.forEach { m ->
                 val q = m.annotations.firstOrNull { it.qualifiedName?.endsWith("Query") == true }
                 if (q != null) println("@Query ${'$'}{m.name}: " + (q.findAttributeValue("value")?.text ?: ""))
             }
             ```
             ```kotlin
             // Discover REST endpoint mappings in a Spring controller via PSI (PREFERRED over string-searching source)
             // Correctly handles class-level @RequestMapping + method-level @GetMapping combinations
             import com.intellij.psi.search.GlobalSearchScope
             val controllerClass = readAction {
                 JavaPsiFacade.getInstance(project).findClass(
                     "com.example.api.controllers.FeatureReactionController",
                     GlobalSearchScope.projectScope(project)
                 )
             }
             readAction {
                 controllerClass?.methods?.forEach { method ->
                     val ann = method.annotations.firstOrNull { a ->
                         listOf("GetMapping","PostMapping","DeleteMapping","PutMapping","PatchMapping","RequestMapping")
                             .any { a.qualifiedName?.endsWith(it) == true }
                     }
                     if (ann != null) {
                         val path = ann.findAttributeValue("value")?.text ?: ann.findAttributeValue("path")?.text ?: "\"\""
                         println("${'$'}{method.name}: ${'$'}{ann.qualifiedName?.substringAfterLast('.')} ${'$'}path")
                     }
                 }
             }
             ```
             ```kotlin
             // Targeted file read — extract only assertions/endpoints to avoid context bloat
             val testContent = VfsUtil.loadText(findProjectFile("src/test/java/com/example/MyTest.java")!!)
             testContent.lines()
                 .filter { it.contains("assert") || it.contains("/api/") || it.contains("@Test") }
                 .forEach { println(it) }
             ```

             **Workflow: After creating/modifying files — verify compile BEFORE running tests:**
             ```kotlin
             // Fast compile check (seconds) — run this BEFORE ./mvnw to catch errors early:
             val vf = findProjectFile("src/main/java/com/example/NewClass.java")!!
             val problems = runInspectionsDirectly(vf)
             if (problems.isEmpty()) println("OK: no compile errors")
             else problems.forEach { (id, descs) -> descs.forEach { println("[${'$'}id] ${'$'}{it.descriptionTemplate}") } }
             ```
             **⚠️ Scope limitation**: `runInspectionsDirectly` is **file-scoped** — it only checks the single
             file you pass. It does NOT catch compile errors in other files that reference the changed
             signatures. After modifying a widely-used class (DTO, command, entity), also check dependent
             files, or run `./mvnw test-compile -q` (with takeLast() truncation) for project-wide verification.

             **⚠️ `runInspectionsDirectly` also catches Spring issues**: Duplicate `@Bean` definitions, missing `@Component` annotations, unresolved `@Autowired` dependencies. Run it on your `@Configuration` classes **BEFORE** `./mvnw test` to catch Spring bean override exceptions early (~5s vs ~90s per Maven cold-start). This surfaces `NoUniqueBeanDefinitionException`-class bugs before they cause confusing test failures.

             **Verification gate**: Run FAIL_TO_PASS tests before marking work complete.
             `./mvnw test -Dtest=ClassName -Dspotless.check.skip=true` (Maven) or `./gradlew :module:test --tests "com.example.ClassName" --no-daemon` (Gradle) — compile success alone is NOT sufficient.

             **⚠️ Deprecation warnings ≠ errors**: Compiler output like `warning: 'getVirtualFilesByName(...)' is deprecated` is non-fatal — the script succeeded. Only retry on explicit `ERROR` responses with no `execution_id`. Do NOT retry a successful execution because of deprecation warnings.

             **Common Operations:**
             - Code navigation: Find usages, go to definition, symbol search
             - Refactoring: Rename, extract method, move files
             - Inspections: Run code analysis, get warnings/errors
             - Tests: Execute tests, inspect results
             - Actions: Trigger any IDE action programmatically

             **Example:**
             ```kotlin
             val file = findProjectFile("src/Main.kt")
             val text = readAction {
                 PsiManager.getInstance(project).findFile(file!!)?.text
             }
             println("File length: " + text?.length)
             ```

             **Best Practice: Use Sub-Agents**
             For complex IntelliJ API work, delegate to a sub-agent:
             - Sub-agent can retry without polluting your context
             - Errors stay isolated
             - Provide detailed 'reason' parameter

             **Resources:**
             - [Complete Coding Guide]($codingGuideUri) - Patterns, examples, best practices
             - [API Power User Guide]($skillUri) - Essential patterns
             - [Debugger Guide]($debuggerUri) - Debug workflows
             - [Test Runner Guide]($testUri) - Test execution

             IntelliJ API Version: ${ApplicationInfo.getInstance().apiVersion}

             **When to use other steroid tools instead:**
             - steroid_list_projects — list open projects and their paths
             - steroid_list_windows — check window state, indexing progress, modal dialogs
             - steroid_open_project — open a project directory in the IDE
             - steroid_action_discovery — discover quick-fixes, intentions, and actions at a file location
             - steroid_take_screenshot / steroid_input — visual UI inspection and interaction

             **Avoiding duplicate submissions**: If the tool response contains an `execution_id`, the call succeeded — do **not** resubmit the same code. Identical consecutive calls waste turns and produce no different result. Only retry if the response contains an explicit `ERROR` with no `execution_id`.

             **steroid_execute_feedback — call for meaningful events only**: Use `steroid_execute_feedback` with `execution_id` + `success_rating` when you encounter a real pattern worth reporting — an API compile error, unexpected IDE behavior, or a significant success (tests passing after your fix). **Do NOT call after routine file reads, trivial one-liners, or when you have nothing specific to report.** Empty feedback stubs waste a round-trip (~20s) and inflate call counts without adding value.
         """.trim().lines().joinToString("\n") { it.trim() }
    }

    override fun register(server: McpServerCore) {
        server.toolRegistry.registerTool(
            name = "steroid_execute_code",
            description = toolDescription,
            inputSchema = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("project_name") {
                        put("type", "string")
                        put("description", "Project name (from steroid_list_projects)")
                    }
                    putJsonObject("code") {
                        put("type", "string")
                        put("description", "Kotlin suspend method body")
                    }
                    putJsonObject("task_id") {
                        put("type", "string")
                        put("description", "Your task identifier to group related executions. Use the same task_id for all execute_code calls that are part of the same task, and when providing feedback via steroid_execute_feedback.")
                    }
                    putJsonObject("reason") {
                        put("type", "string")
                        put("description", "IMPORTANT: On your FIRST call, provide the FULL TASK DESCRIPTION from the user - what they originally asked you to do. On subsequent calls, describe what this specific execution aims to achieve. This helps track progress and understand context.")
                    }
                    putJsonObject("timeout") {
                        put("type", "integer")
                        put("description", "Execution timeout in seconds (default: 600, configurable via mcp.steroid.execution.timeout registry key)")
                    }
                    putJsonObject("dialog_killer") {
                        put("type", "boolean")
                        put("description", "Override pre-execution dialog killer: true = force enable, false = force disable. Default: use registry setting (mcp.steroid.dialog.killer.enabled).")
                    }
                    putJsonObject("required_plugins") {
                        put("type", "array")
                        putJsonObject("items") {
                            put("type", "string")
                        }
                        put(
                            "description",
                            "Optional list of required plugin IDs (example: com.intellij.database). " +
                                "Use steroid_capabilities to list installed plugins."
                        )
                    }
                }
                putJsonArray("required") {
                    add("project_name")
                    add("code")
                    add("reason")
                    add("task_id")
                }
            },
            ::handle
        )
    }

    private suspend fun handle(context: ToolCallContext): ToolCallResult {
        val params = context.params
        val args = params.arguments ?: return errorResult("Missing arguments")

        val projectName = args["project_name"]?.jsonPrimitive?.contentOrNull
            ?: return errorResult("Missing required parameter: project_name")
        val code = args["code"]?.jsonPrimitive?.contentOrNull
            ?: return errorResult("Missing required parameter: code")
        val taskId = args["task_id"]?.jsonPrimitive?.contentOrNull
            ?: return errorResult("Missing required parameter: task_id")
        val reason = args["reason"]?.jsonPrimitive?.contentOrNull
        val timeout = args["timeout"]?.jsonPrimitive?.intOrNull ?: Registry.intValue("mcp.steroid.execution.timeout", 600)
        val dialogKiller = args["dialog_killer"]?.jsonPrimitive?.booleanOrNull
        val requiredPlugins = args["required_plugins"]
            ?.jsonArray
            ?.mapNotNull { it.jsonPrimitive.contentOrNull }
            ?.filter { it.isNotBlank() }
            ?: emptyList()

        val missingPlugins = findMissingPlugins(requiredPlugins)
        if (missingPlugins.isNotEmpty()) {
            return errorResult(
                "Missing required plugins: ${missingPlugins.joinToString(", ")}. " +
                    "Use steroid_capabilities to list installed plugins."
            )
        }

        val project = readAction {
            getInstance().openProjects.find { it.name == projectName }
        }
            ?: return errorResult("Project not found: $projectName")

        val execCodeParams = ExecCodeParams(
            taskId = taskId,
            code = code,
            reason = reason ?: "No reason provided",
            timeout = timeout,
            dialogKiller = dialogKiller,
            rawParams = params.arguments
        )

        val result = project
            .service<ExecutionManager>()
            .executeWithProgress(execCodeParams)

        runCatching {
            analyticsBeacon.capture(
                event = "exec_code",
                project = project,
                properties = mapOf(
                    "result" to if (result.isError) "error" else "success"
                )
            )
        }

        return result
    }

    private fun errorResult(message: String) = ToolCallResult(
        content = listOf(ContentItem.Text(text = "ERROR: $message")),
        isError = true
    )

    private fun findMissingPlugins(requiredPlugins: List<String>): List<String> {
        if (requiredPlugins.isEmpty()) return emptyList()
        return requiredPlugins.filter { pluginId ->
            val resolvedId = PluginId.getId(pluginId)
            val resolved = PluginManagerCore.getPlugin(resolvedId)
            resolved == null || !PluginManagerCore.isLoaded(resolvedId)
        }
    }

}
