Execute Code Tool

MCP tool description for the steroid_execute_code tool.

###_NO_AUTO_TOC_###
Execute Kotlin code directly in IntelliJ's runtime with full API access — builds, tests, refactoring, inspections, debugging, navigation.

## 🛑 STOP before the 2nd native `Edit` — use `applyPatch`

If you are about to make similar edits across **two or more files** (same pattern, different paths), **do not chain `Edit` calls**. Switch to a single `steroid_execute_code` call with the `applyPatch` DSL:

```kotlin
val result = applyPatch {
    hunk("/abs/path/A.java",  "oldA", "newA")
    hunk("/abs/path/B.java",  "oldB", "newB")
    hunk("/abs/path/C.java",  "oldC", "newC")
}
println(result)
```

**When the same old → new applies to N files** (common — `@ComponentScan` added to every `*Application.java`, same logger refactor in every service), loop over paths so the old/new pair ships ONCE instead of N times:

```kotlin
val old = "@SpringBootApplication\npublic class"
val new = "@SpringBootApplication\n@ComponentScan(\"shop\")\npublic class"
applyPatch {
    listOf("/abs/A.java", "/abs/B.java", "/abs/C.java", "/abs/D.java")
        .forEach { hunk(it, old, new) }
}
```

Per `mcp-steroid://ide/apply-patch`: ~40% token cut for shared-pattern edits vs naive `hunk; hunk; hunk; hunk`. Pre-flight catches non-unique anchors with `ApplyPatchException`, so you can safely keep `old` to the shortest unique signature (30–60 chars usually — no need for the full 300-char safety block).

One undoable command. Pre-flight validates every `oldString` exists exactly once. PSI committed in the same call. Native `Edit` chains bypass the VFS, leave PSI stale, and cost you one tool call per site.

**Heuristic**: before the 2nd `Edit` in the same task, stop and ask: "Am I applying the same or similar change to 2+ files?" If yes, `applyPatch { … }` in one call. If the old/new is shared, `forEach`.

## Decision tree — pick the IDE path before reaching for a native tool

| Task shape | One-line IDE call |
|---|---|
| **Two or more literal-text edits, same or different files** | `applyPatch { hunk(pathA, oldA, newA); hunk(pathB, oldB, newB); … }` — atomic undo, pre-flight validation, PSI commit. Use whenever an `Edit`/`Edit`/`Edit` chain is tempting. |
| **One literal-text edit, single file** | `val vf = findProjectFile(p)!!; writeAction { VfsUtil.saveText(vf, String(vf.contentsToByteArray(), vf.charset).replace(OLD, NEW)) }` |
| **Find files by extension** | `FilenameIndex.getAllFilesByExt(project, "java", projectScope())` — not `Bash find … -name "*.java"` |
| **Find files by exact name** | `FilenameIndex.getVirtualFilesByName("UserService.java", projectScope())` |
| **Find all references to a symbol** | `ReferencesSearch.search(psiElement, projectScope())` — type-aware; Grep over source text is a fallback |
| **Read file content (any size)** | `String(findProjectFile(p)!!.contentsToByteArray(), charset)` — stays inside the IDE; the next semantic query sees what you read |
| **Grep content inside project files** | `FilenameIndex.getAllFilesByExt(project, ext, scope).flatMap { vf -> Regex(pat).findAll(String(vf.contentsToByteArray(), vf.charset)) … }` in ONE call |
| **Run Maven / Gradle tests** | IDE runner — see the Maven / Gradle recipes below; Bash `./mvnw test` is last resort |
| **Compile check after an edit** | `ProjectTaskManager.getInstance(project).buildAllModules().await()` |
| **Git / Docker CLI / shell** | native `Bash` — genuinely outside the IDE |

If your next instinct is a native `Read` / `Edit` / `Grep` / `Glob` / `Bash` call, check this table first. The IDE path keeps VFS + PSI consistent, reuses the warm JVM, and one call reliably replaces 3-5 chained native-tool calls.

**Before your first call, read the guide for your task** with `steroid_fetch_resource`:
- Building/testing → `mcp-steroid://prompt/test-skill`
- Debugging → `mcp-steroid://prompt/debugger-skill`
- Any IDE task → `mcp-steroid://prompt/skill`

**Quick Start:**
- Code is a suspend function body (never use runBlocking)
- `waitForSmartMode()` runs automatically
- Available: `project`, `println()`, `printJson()`, `progress()`

**Threading rules — apply preventively, not after an error:**

| You are about to… | Wrap the call in… |
|---|---|
| Read any PSI element / walk a PSI tree / navigate references | `readAction { }` |
| Write to a VFS file (`VfsUtil.saveText`, `vf.setBinaryContent`) | `writeAction { }` |
| Invoke a refactoring processor's `.run()` (Rename / Move / SafeDelete / Inline / ChangeSignature / Extract*) | `writeIntentReadAction { }` — NOT `writeAction`; the processor manages its own actions internally, and `writeAction` deadlocks |
| Commit pending document edits to PSI | `writeAction { PsiDocumentManager.getInstance(project).commitAllDocuments() }` (usually as the line *after* the refactor) |
| Use a `CommandProcessor.executeCommand { … }` block (undo-grouping) | put the command inside the appropriate read/write action — `executeCommand` itself is *not* an action |

A correctly-wrapped call produces the right result on the first try. An incorrectly-wrapped call throws `Read access is allowed from inside read-action only` or hangs indefinitely — both waste a retry turn.

**Compile check** (use after every edit — do NOT use `./mvnw compile`):

```kotlin
import com.intellij.task.ProjectTaskManager
import org.jetbrains.concurrency.await

val result = ProjectTaskManager.getInstance(project).buildAllModules().await()
println("Compile errors: ${result.hasErrors()}, aborted: ${result.isAborted()}")
```

**Run tests via the IDE runner, not Bash.** `./mvnw test` / `./gradlew test` cold-start ~31 s per invocation. The IDE runner keeps the JVM warm and returns structured pass/fail:

```kotlin
// Maven — single test class or method via the IDE's Maven runner:
import com.intellij.openapi.externalSystem.service.execution.ExternalSystemRunConfiguration
import com.intellij.openapi.externalSystem.model.execution.ExternalSystemTaskExecutionSettings
import org.jetbrains.idea.maven.execution.MavenRunConfigurationType
import com.intellij.execution.ProgramRunnerUtil
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.execution.RunManager

val cfg = MavenRunConfigurationType.getInstance().configurationFactories.single()
    .createTemplateConfiguration(project) as org.jetbrains.idea.maven.execution.MavenRunConfiguration
cfg.name = "Run PetRestControllerTests"
cfg.runnerParameters.workingDirPath = project.basePath!!
cfg.runnerParameters.goals = listOf("test", "-Dtest=PetRestControllerTests", "-Dspotless.check.skip=true")
val settings = RunManager.getInstance(project).createConfiguration(cfg, cfg.factory!!)
ProgramRunnerUtil.executeConfiguration(settings, DefaultRunExecutor.getRunExecutorInstance())
```

For deeper patterns (SMTRunner listeners that block until tests finish + emit structured JSON results) fetch `mcp-steroid://skill/coding-with-intellij-spring`. Bash `./mvnw test` is only OK as a last-resort when the IDE runner has genuinely failed for the scenario.

**After a compile error**: fix and retry. Common fixes:
- `suspension functions can only be called within coroutine body` → mark helper as `suspend fun`
- `unresolved reference` → add missing import
- `Write access is allowed from write thread only` → wrap in `writeAction { }`
- `Read access is allowed from inside read-action only` → wrap in `readAction { }`

**File discovery**: `FilenameIndex.getAllFilesByExt(project, ext, projectScope())` or `FilenameIndex.getVirtualFilesByName(name, projectScope())` inside `steroid_execute_code` — O(1) indexed lookup over the same VFS your next write will touch.
**File reading**: `String(findProjectFile(relPath)!!.contentsToByteArray(), charset)` inside `steroid_execute_code` — single call, stays inside the IDE so PSI is consistent if you read the same file again later. The native `Read` tool is a valid alternative but imposes the Read-before-Edit contract only it tracks; staying inside `steroid_execute_code` avoids that coupling entirely.
**In-place file editing (ANY size, 1–1000+ lines)**: use steroid_execute_code — do NOT use the native `Edit` tool. The native `Edit` writes to disk bypassing IntelliJ, leaving VFS + PSI stale; every following semantic query sees the old content until you force a refresh. The IDE-side recipe below is ~5 lines of real code, same payload shape as `Edit(old, new)`, reads+writes inside one call, and the VFS auto-refreshes so PSI stays consistent:

```kotlin
val vf = findProjectFile("src/main/java/com/example/MyClass.java")!!
val content = String(vf.contentsToByteArray(), vf.charset)  // read
val updated = content.replace("OLD_STRING", "NEW_STRING")
check(updated != content) { "no match for OLD_STRING — verify with Grep first" }
writeAction { VfsUtil.saveText(vf, updated) }               // write + VFS refresh
```

For exactly-one-occurrence replace: `.replace(OLD, NEW).also { check(… == 1 occurrence) }`. For regex: `Regex(pattern).replace(content, replacement)`. Do NOT pre-Read the file via the native tool before using this recipe — the `vf.contentsToByteArray()` read already covers that.

**Two or more edits in one or more files**: use the `applyPatch { hunk(...) }` DSL that's a member of every `steroid_execute_code` script context — N literal-text substitutions, one undoable command, all-or-nothing pre-flight. Zero imports, ~5 lines of Kotlin for a 3-hunk patch:

```kotlin
val result = applyPatch {
    hunk("/abs/path/A.java", "oldA", "newA")
    hunk("/abs/path/A.java", "oldA2", "newA2")
    hunk("/abs/path/B.java", "oldB", "newB")
}
println(result)   // per-hunk path:line:col audit
```

Read `mcp-steroid://ide/apply-patch` for full semantics (pre-flight validation, descending-offset ordering per file, `ApplyPatchException` on missing/non-unique `oldString`).

**VFS refresh before and after every call.** MCP Steroid schedules two refreshes for you:
- **Before** kotlinc compiles your script, the plugin **awaits** a `VfsUtil.markDirtyAndRefresh` on the project root so the compiler sees every on-disk change made by a peer process or the previous call. Blocking, capped at 30 s.
- **After** your script returns — from a `finally` block, so this runs on success AND failure paths — the plugin fires a non-blocking async refresh. The MCP response returns immediately; the next semantic query sees the up-to-date state on the `RefreshQueue` thread.

You do **not** need to schedule VFS refresh yourself. You still need `PsiDocumentManager.getInstance(project).commitAllDocuments()` inside your script if the same script both writes and reads back PSI — the tail auto-refresh runs _after_ your script finishes.

**Payload accounting for this recipe.** The `steroid_execute_code` tool input carries only the Kotlin **script source** (typically ~200–400 chars for an in-place edit — 5 lines of code + a path + OLD/NEW strings). The file bytes that `vf.contentsToByteArray()` reads and the `updated` content that `saveText(vf, updated)` writes live inside the IDE JVM and never cross the MCP boundary — do NOT double-count them against the payload budget. For a 1-line change in a 160-line file, the `Edit` tool ships old_string + new_string (~60 bytes) and the recipe ships ~300 bytes of script — roughly 5× on the script itself, but you save the otherwise-required pre-Read (~3600 bytes for that 160-line file) and keep the IDE's VFS consistent. Net payload is **smaller**, not larger.

💡 Call `steroid_execute_feedback` after execution to rate success.
