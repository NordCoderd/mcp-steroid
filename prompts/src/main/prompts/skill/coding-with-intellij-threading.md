Coding with IntelliJ: Threading and Read/Write Actions

IntelliJ threading model, read/write action patterns, smart mode, modal dialogs, and ModalityState usage.

## Threading and Read/Write Actions

> **⚠️ THREADING RULE — NEVER SKIP**: Any PSI access (JavaPsiFacade, PsiShortNamesCache, PsiManager.findFile, module roots, annotations, etc.) **MUST** be wrapped in `readAction { }`. Modifications require `writeAction { }`. Threading violations throw immediately at runtime — they are not silently ignored. This is the most common first-attempt error when writing IntelliJ scripts.

### IntelliJ Threading Model

IntelliJ Platform has strict threading rules:

1. **EDT (Event Dispatch Thread)** - UI updates only
2. **Read actions** required for PSI/VFS reads
3. **Write actions** required for PSI/VFS writes
4. **Smart mode** required for index-dependent operations

**See**: [IntelliJ Threading Rules](https://plugins.jetbrains.com/docs/intellij/general-threading-rules.html)

### Using Built-in Read Actions

```kotlin
import com.intellij.openapi.vfs.LocalFileSystem

// CORRECT - Use built-in readAction (no import needed)
val virtualFile = LocalFileSystem.getInstance().findFileByPath("/path/to/File.kt")!!
val psiFile = readAction {
    PsiManager.getInstance(project).findFile(virtualFile)
}
println("PSI file: ${psiFile?.name}")
```

### Using Built-in Write Actions

```kotlin
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.command.WriteCommandAction

val vf = LocalFileSystem.getInstance().findFileByPath("/path/to/file.kt")!!
val document = FileDocumentManager.getInstance().getDocument(vf)!!

// CORRECT - Use built-in writeAction (no import needed)
writeAction {
    document.setText("new content")
}

// For command-wrapped writes (shows in undo stack)
WriteCommandAction.runWriteCommandAction(project) {
    document.replaceString(0, 0, "// Added comment\n")
}
```

**⚠️ writeAction { } is NOT a coroutine scope**: Calling `readAction { }`, `VfsUtil.saveText()`, or ANY suspend function inside `writeAction { }` throws:
```
suspension functions can only be called within coroutine body
```
This error appears at **runtime** (not at compile time), so it's easy to miss. The fix is simple — always **read first, write second**:

```kotlin
// WRONG — readAction inside writeAction causes runtime error:
//
//   writeAction {
//       val text = readAction { String(vf.contentsToByteArray(), vf.charset) }  // ERROR: suspend function in non-coroutine
//       VfsUtil.saveText(vf, text.replace("old", "new"))
//   }
//
// This fails at runtime because writeAction { } is NOT a coroutine scope,
// and readAction is a suspend function. See CORRECT version below.
```

```kotlin
// CORRECT — read outside, write inside
val vf = findProjectFile("src/main/resources/application.properties")!!
val text = String(vf.contentsToByteArray(), vf.charset)  // read OUTSIDE writeAction
val updated = text.replace("\"api\"", "\"/api/v1\"")
writeAction { VfsUtil.saveText(vf, updated) }   // write INSIDE — no suspend calls allowed here
```

**Note**: `String(vf.contentsToByteArray(), vf.charset)` is a regular call (not suspend) — it's safe to call outside any action. `VfsUtil.saveText(vf, text)` is also a regular function but requires a write lock, so it must go inside `writeAction { }`.

If you genuinely need suspend calls inside a write block, use `edtWriteAction { }` instead of `writeAction { }` — it is a suspend function that acquires the write lock.

### Smart Read Actions (Recommended)

Use `smartReadAction` when you need both smart mode and read access:

```kotlin[IU]
import org.jetbrains.kotlin.idea.stubindex.KotlinClassShortNameIndex

// RECOMMENDED - Combines waitForSmartMode() + readAction in one call
val classes = smartReadAction {
    KotlinClassShortNameIndex.get("MyService", project, projectScope())
}
println("Found ${classes.size} classes")
```

### Smart Mode vs Dumb Mode

During indexing, the IDE is in "dumb mode" - many APIs are unavailable:
```kotlin
import com.intellij.openapi.project.DumbService

if (DumbService.isDumb(project)) {
    println("IDE is indexing - indices not available")
} else {
    println("Smart mode - all APIs available")
}
```

**Good news**: `waitForSmartMode()` is called automatically before your script starts!

### Modal Dialogs and ModalityState

When a modal dialog is open in the IDE, the default EDT dispatcher (`Dispatchers.EDT`) will
**not execute** your code — it waits until the dialog is dismissed. To interact with the IDE
while a modal dialog is present, use `ModalityState.any()`:
```kotlin
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.asContextElement
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// ✓ CORRECT - Runs on EDT even when a modal dialog is showing
withContext(Dispatchers.EDT + ModalityState.any().asContextElement()) {
    // Enumerate windows, inspect dialogs, close dialogs, etc.
}
```

**When to use `ModalityState.any()`:**
- Enumerating open windows or dialogs while a modal is present
- Taking screenshots when a dialog is blocking the IDE
- Closing modal dialogs programmatically (e.g., `dialog.close(...)`)
- Any EDT work that must run regardless of modal state

**When NOT to use it:**
- Normal UI operations — use plain `Dispatchers.EDT` instead
- Read/write actions — use `readAction { }` / `writeAction { }` instead

**Detecting modal dialogs:**
```kotlin
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.asContextElement
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

withContext(Dispatchers.EDT + ModalityState.any().asContextElement()) {
    val isModal = ModalityState.current() != ModalityState.nonModal()
    println("Modal dialog showing: $isModal")
}
```

---
