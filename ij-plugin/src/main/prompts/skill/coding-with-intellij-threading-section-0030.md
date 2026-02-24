
**⚠️ writeAction { } is NOT a coroutine scope**: Calling `readAction { }`, `VfsUtil.saveText()`, or ANY suspend function inside `writeAction { }` throws:
```
suspension functions can only be called within coroutine body
```
This error appears at **runtime** (not at compile time), so it's easy to miss. The fix is simple — always **read first, write second**:

```kotlin
// ✗ WRONG — readAction inside writeAction causes runtime error
writeAction {
    val text = readAction { VfsUtil.loadText(vf) }  // ERROR: suspend function in non-coroutine
    VfsUtil.saveText(vf, text.replace(...))
}

// ✓ CORRECT — read outside, write inside
val text = VfsUtil.loadText(vf)           // read OUTSIDE writeAction (VfsUtil.loadText is NOT suspend)
val updated = text.replace("\"api\"", "\"/api/v1\"")
writeAction { VfsUtil.saveText(vf, updated) }   // write INSIDE — no suspend calls allowed here
```
