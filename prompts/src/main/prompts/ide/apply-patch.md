IDE: Apply Patch — Atomic Multi-Site Edit

Apply N substring replacements across one or more files as a single undoable command with PSI + VFS kept in sync.

```kotlin
import com.intellij.openapi.command.CommandProcessor

// Configuration — modify for your use case.
// Each triple is (absolute_path, old_string, new_string).
// old_string is matched LITERALLY (no regex). If it occurs multiple times in the file,
// expand old_string with surrounding context until it is unique, or you'll get a
// "non-unique" error below.
data class PatchHunk(val filePath: String, val oldString: String, val newString: String)

val patches = listOf(
    PatchHunk(
        filePath = "/path/to/Service.java",          // TODO
        oldString = "LoggerFactory.getLogger(\"old\")", // TODO
        newString = "LoggerFactory.getLogger(Service.class)", // TODO
    ),
    PatchHunk(
        filePath = "/path/to/Service.java",
        oldString = "log.warn(\"deprecated\")",
        newString = "log.info(\"v2\")",
    ),
    PatchHunk(
        filePath = "/path/to/OtherService.java",
        oldString = "LoggerFactory.getLogger(\"old\")",
        newString = "LoggerFactory.getLogger(OtherService.class)",
    ),
)

// Pre-flight: resolve every path + document, count old_string occurrences, and
// fail FAST before any edit happens — the whole operation must be atomic.
// Resolve line/column here too (while we already hold the read action) so the
// success line-number output does not require a second read-action turn.
data class ResolvedHunk(
    val path: String,
    val document: com.intellij.openapi.editor.Document,
    val offset: Int,
    val oldLen: Int,
    val newString: String,
    val line: Int,      // 1-based, captured pre-edit under readAction
    val column: Int,    // 1-based
)

val resolved: List<ResolvedHunk> = readAction {
    patches.mapIndexed { index, hunk ->
        val vf = findFile(hunk.filePath)
            ?: error("Hunk #$index: file not found: ${hunk.filePath}")
        val document = FileDocumentManager.getInstance().getDocument(vf)
            ?: error("Hunk #$index: no Document for ${hunk.filePath}")
        val text = document.text
        val first = text.indexOf(hunk.oldString)
        require(first >= 0) {
            "Hunk #$index: old_string not found in ${hunk.filePath} — verify with Grep first"
        }
        val second = text.indexOf(hunk.oldString, first + 1)
        require(second < 0) {
            "Hunk #$index: old_string occurs more than once in ${hunk.filePath} " +
                "(first at offset $first, next at $second) — expand old_string with surrounding context to make it unique"
        }
        val lineIdx = document.getLineNumber(first)
        val column = first - document.getLineStartOffset(lineIdx)
        ResolvedHunk(
            path = hunk.filePath,
            document = document,
            offset = first,
            oldLen = hunk.oldString.length,
            newString = hunk.newString,
            line = lineIdx + 1,
            column = column + 1,
        )
    }
}

// Apply every hunk inside a single writeAction { } so that the whole batch runs on
// one write pass — suspend-friendly, never dispatches through a blocked EDT, and
// wraps in a single undoable CommandProcessor command. Either every replaceString
// succeeds or (on throw) the command is rolled back; Undo reverts the batch in
// one step. Multi-hunk edits in the same file must be applied in descending
// offset order so earlier replacements don't shift later offsets.
val groupedDescendingOffset = resolved.groupBy { it.document }
    .mapValues { (_, hunks) -> hunks.sortedByDescending { it.offset } }

val commandName = "MCP Steroid: apply-patch (${resolved.size} hunk${if (resolved.size == 1) "" else "s"})"

writeAction {
    CommandProcessor.getInstance().executeCommand(project, {
        for ((_, hunksInFile) in groupedDescendingOffset) {
            for (h in hunksInFile) {
                h.document.replaceString(h.offset, h.offset + h.oldLen, h.newString)
            }
        }
    }, commandName, null)
    // Flush Document edits into PSI inside the same write action so any subsequent
    // semantic query in this script (or the next steroid_execute_code call) sees
    // the new tree. The tail-of-exec async VFS refresh handles on-disk sync.
    PsiDocumentManager.getInstance(project).commitAllDocuments()
}

// Per-hunk audit trail — pre-computed line/column lets the caller verify exactly
// which sites were rewritten without a follow-up Read.
println("apply-patch: ${resolved.size} hunk${if (resolved.size == 1) "" else "s"} across ${groupedDescendingOffset.size} file(s) applied atomically.")
resolved.forEachIndexed { index, h ->
    println("  [#$index] ${h.path}:${h.line}:${h.column}  (${h.oldLen}→${h.newString.length} chars)")
}
```

## When to use this vs other patterns

- **Single occurrence in one file, no cross-file impact**: the compact
  `findProjectFile + String.replace + VfsUtil.saveText` pattern in the
  `steroid_execute_code` tool description is shorter and equally safe.
- **Semantic rename with type-aware reference chasing**: use
  [`mcp-steroid://lsp/rename`](mcp-steroid://lsp/rename) — `RenameProcessor`
  knows about imports, overrides, and method references that `apply-patch`'s
  literal-text match cannot see.
- **2+ edits in the same file OR the same literal across several files**:
  this recipe. One `WriteCommandAction`, one entry in the undo stack,
  all-or-nothing atomicity, PSI committed in the same call.

## Why `writeAction { CommandProcessor.executeCommand { … } }` (Design B)

- **Suspend-friendly, no blocked-EDT deadlock**: `writeAction { }` is the
  coroutine-aware write entry point — it dispatches through the coroutine's
  own thread pool, not through the blocking `WriteCommandAction.run { }`
  path which re-enters the EDT. The earlier EDT re-entry deadlocked in
  agent-via-CLI harnesses where the EDT was already pumping another
  blocking call. **Autoresearch finding (Claude, iter-01)** confirmed this
  by observing a silent hang on `WriteCommandAction.run` in the test runner.
- **Atomicity via `CommandProcessor.executeCommand`**: wrapping the edit
  loop in `CommandProcessor.executeCommand(project, …, commandName, …)`
  opens one command (see
  `platform/core-api/src/com/intellij/openapi/command/CommandProcessor.java`);
  all `Document.replaceString` calls combine into a single undoable step
  and the PSI/VFS change notifications are coalesced.
- **In-memory Document model, not raw VFS bytes**: edits flow through
  `Document` → `PsiDocumentManager.commitAllDocuments()` → PSI tree, which
  is what every IntelliJ inspection / find-references / refactor reads.
  `VfsUtil.saveText` bypasses that layer and requires an explicit refresh.
- **Per-site granularity on pre-flight**: if one hunk's `old_string` is not
  unique or not found, the pre-flight `require` fails BEFORE any edit is
  applied — no partial state.
- **Descending offset order per file**: a single `Document` shifts offsets
  as it is edited; applying hunks bottom-up keeps earlier offsets valid.
  The recipe does this automatically via `sortedByDescending { it.offset }`.
- **Per-hunk line/column audit in the success message** (added in iter-01
  after both Claude and Codex flagged that `N hunks across M files` alone
  did not let the caller verify WHICH sites were rewritten): the recipe
  captures line/column under the same read action that validates the
  hunk, so the final output lists
  `[#i] path:line:column  (oldLen→newLen chars)` for every applied hunk.

## Caveats

- `old_string` is matched **literally** (no regex, no whitespace tolerance).
  For regex-style patterns use a single hunk with
  `val text = document.text.let { Regex(pattern).replace(it, replacement) }`
  and a `document.replaceString(0, document.textLength, text)`.
- Hunks that overlap within the same file are disallowed — the pre-flight
  counts occurrences, not overlap. If you want overlapping edits, merge them
  into a single hunk.
- The post-execution fire-and-forget VFS refresh (wired into every
  `steroid_execute_code` call by MCP Steroid) picks up any out-of-band disk
  writes after this recipe returns — you don't need to force a refresh yourself.

# See also

- [LSP Rename — semantic RenameProcessor](mcp-steroid://lsp/rename)
- [Move Class](mcp-steroid://ide/move-class)
- [Change Signature](mcp-steroid://ide/change-signature)
- [IntelliJ API Power User Guide](mcp-steroid://prompt/skill)
