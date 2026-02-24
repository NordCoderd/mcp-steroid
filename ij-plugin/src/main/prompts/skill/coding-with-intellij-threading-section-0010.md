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
// ✓ CORRECT - Use built-in readAction (no import needed)
val psiFile = readAction {
    PsiManager.getInstance(project).findFile(virtualFile)
}

// Also correct - but built-in is more convenient
import com.intellij.openapi.application.readAction
val psiFile = readAction {
    PsiManager.getInstance(project).findFile(virtualFile)
}
```
