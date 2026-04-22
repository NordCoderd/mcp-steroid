Execute Code Tool

MCP tool description for the steroid_execute_code tool.

###_NO_AUTO_TOC_###
Execute Kotlin code directly in IntelliJ's runtime with full API access — builds, tests, refactoring, inspections, debugging, navigation.

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

**Do NOT use Bash `./mvnw test` or `./gradlew test`** — read `mcp-steroid://prompt/test-skill` for IDE test runner patterns that save ~31s per invocation.

**After a compile error**: fix and retry. Common fixes:
- `suspension functions can only be called within coroutine body` → mark helper as `suspend fun`
- `unresolved reference` → add missing import
- `Write access is allowed from write thread only` → wrap in `writeAction { }`
- `Read access is allowed from inside read-action only` → wrap in `readAction { }`

**File discovery INSIDE steroid_execute_code**: use `FilenameIndex` (O(1) indexed), not filesystem scan.
**File reading by known path**: use native `Read` tool (zero overhead), not steroid_execute_code.
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
