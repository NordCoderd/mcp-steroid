# Execute Code: Threading Rules

## Quick Start

Your code is a **suspend function body** (never use `runBlocking`):
- Use `readAction { }` for PSI/VFS reads, `writeAction { }` for modifications
- `waitForSmartMode()` runs automatically before your script
- Available: `project`, `println()`, `printJson()`, `printException()`, `progress()`

**⚠️ Helper functions that call `readAction`/`writeAction` MUST be `suspend fun`** — a regular `fun` that calls these gets a compile error: `"suspension functions can only be called within coroutine body"`. This applies to ALL suspend-context APIs: `readAction`, `writeAction`, `smartReadAction`, `waitForSmartMode`, `runInspectionsDirectly`.

---

## ⚠️ THREADING RULE — NEVER SKIP

Any PSI access (`JavaPsiFacade`, `PsiShortNamesCache`, `PsiManager.findFile`, `ProjectRootManager.contentSourceRoots`, module roots, annotations, etc.) **MUST** be wrapped in `readAction { }`. Modifications require `writeAction { }`. Threading violations throw immediately at runtime — they are not silently ignored. **This applies to ALL PSI calls including your very first exploration call** (e.g. listing source roots). This is the most common first-attempt error.

---

## ⚠️ writeAction { } Is NOT a Coroutine Scope

Calling `readAction { }` or ANY suspend function inside `writeAction { }` throws `suspension functions can only be called within coroutine body`. **ALWAYS read first (outside), then write (inside)**:
