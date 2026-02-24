---

## Best Practices

### 1. Use smartReadAction for PSI Operations

```kotlin
// ✓ RECOMMENDED - combines wait + read in one call
val classes = smartReadAction {
    KotlinClassShortNameIndex.get("MyClass", project, projectScope())
}

// Instead of:
waitForSmartMode()
val classes = readAction {
    KotlinClassShortNameIndex.get("MyClass", project, projectScope())
}
```

### 2. Use Built-in Helpers

No imports needed for:
- `readAction`, `writeAction`, `smartReadAction`
- `projectScope()`, `allScope()`
- `findPsiFile()`, `findProjectFile()`

### 3. Imports Are Optional

Default imports are provided automatically. Add imports only when needed:

```kotlin
// ✓ CORRECT - top-level imports when needed
import com.intellij.psi.PsiManager

val psiFile = readAction {
    PsiManager.getInstance(project).findFile(virtualFile)
}
```

### 4. Prefer IntelliJ APIs Over File Operations

The IDE has indexed everything - use it!

### 5. Use ONE Consistent task_id for the Entire Task

**Use the SAME `task_id` from your first call to your last.** Do NOT change `task_id` mid-task.

Changing `task_id` resets the MCP server's per-task feedback history and causes you to lose
context of what you've already done. After changing `task_id`, agents frequently re-read all
the same files already in their conversation history — wasting ~20s per redundant call.

**After discovering Docker is unavailable, a compile error, or any other constraint:**
continue with the SAME `task_id` and adapt your strategy (e.g., switch to `runInspectionsDirectly`
for compile verification). Do NOT restart exploration from scratch under a new `task_id`.

Group related executions with the same `task_id`.

### 6. Report Progress
