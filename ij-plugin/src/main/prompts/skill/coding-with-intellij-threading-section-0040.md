
**Note**: `VfsUtil.loadText(vf)` is a regular function (not suspend) — it's safe to call outside any action. `VfsUtil.saveText(vf, text)` is also a regular function but requires a write lock, so it must go inside `writeAction { }`.

If you genuinely need suspend calls inside a write block, use `edtWriteAction { }` instead of `writeAction { }` — it is a suspend function that acquires the write lock.

### Smart Read Actions (Recommended)

Use `smartReadAction` when you need both smart mode and read access:

```kotlin
// ✓ RECOMMENDED - Combines waitForSmartMode() + readAction in one call
val classes = smartReadAction {
    KotlinClassShortNameIndex.get("MyService", project, projectScope())
}

// Equivalent to:
waitForSmartMode()
val classes = readAction {
    KotlinClassShortNameIndex.get("MyService", project, projectScope())
}
```
