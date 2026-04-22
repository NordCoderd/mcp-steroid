IDE: Apply Patch — Atomic Multi-Site Edit

Apply N substring replacements across one or more files as a single undoable command with PSI + VFS kept in sync.

```kotlin
import com.intellij.openapi.command.WriteCommandAction

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
data class ResolvedHunk(
    val path: String,
    val document: com.intellij.openapi.editor.Document,
    val offset: Int,
    val oldLen: Int,
    val newString: String,
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
        ResolvedHunk(
            path = hunk.filePath,
            document = document,
            offset = first,
            oldLen = hunk.oldString.length,
            newString = hunk.newString,
        )
    }
}

// Apply every hunk in a single WriteCommandAction. Either all succeed or (on throw)
// IntelliJ rolls back the whole command; the user presses Undo once to revert the
// entire batch. Multi-hunk edits in the same file must be applied in descending
// offset order so earlier replacements don't shift later offsets.
val groupedDescendingOffset = resolved.groupBy { it.document }
    .mapValues { (_, hunks) -> hunks.sortedByDescending { it.offset } }

WriteCommandAction.writeCommandAction(project)
    .withName("MCP Steroid: apply-patch (${resolved.size} hunk${if (resolved.size == 1) "" else "s"})")
    .run<Exception> {
        for ((_, hunksInFile) in groupedDescendingOffset) {
            for (h in hunksInFile) {
                h.document.replaceString(h.offset, h.offset + h.oldLen, h.newString)
            }
        }
    }

// Flush Document edits into PSI so any subsequent semantic query in this script
// (or in the next steroid_execute_code call) sees the new tree.
writeAction { PsiDocumentManager.getInstance(project).commitAllDocuments() }

println("apply-patch: ${resolved.size} hunks across ${groupedDescendingOffset.size} file(s) applied atomically.")
resolved.groupBy { it.path }.forEach { (path, hs) ->
    println("  $path: ${hs.size} hunk${if (hs.size == 1) "" else "s"}")
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

## Why `Document.replaceString` + `WriteCommandAction` (Design B)

- **Atomicity via `CommandProcessor`**: `WriteCommandAction.run { … }` opens
  one command (see
  `platform/core-api/src/com/intellij/openapi/command/WriteCommandAction.java`);
  all `Document.replaceString` calls inside the lambda combine into a single
  undoable step and the PSI/VFS change notifications are coalesced.
- **In-memory Document model, not raw VFS bytes**: edits flow through
  `Document` → `PsiDocumentManager.commitAllDocuments()` → PSI tree, which
  is what every IntelliJ inspection / find-references / refactor reads.
  `VfsUtil.saveText` bypasses that layer and requires an explicit refresh.
- **Per-site granularity**: if one hunk's `old_string` is not unique or not
  found, the pre-flight `require` fails BEFORE any edit is applied — no
  partial state.
- **Descending offset order per file**: a single `Document` shifts offsets
  as it is edited; applying hunks bottom-up keeps earlier offsets valid.
  The recipe does this automatically via `sortedByDescending { it.offset }`.

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
