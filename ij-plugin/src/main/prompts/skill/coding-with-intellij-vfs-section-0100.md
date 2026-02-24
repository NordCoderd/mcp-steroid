
---

### Create Java/Kotlin Source Files (Preferred Pattern)

Use `VfsUtil.createDirectoryIfMissing` + `VfsUtil.saveText` — safer than shell heredocs and atomic.

**⚠️ ALL VFS mutation ops need writeAction**: `createChildData()`, `createChildFile()`, `createChildDirectory()`, `delete()`, `rename()`, `move()`, and `saveText()` ALL require writeAction. `createDirectoryIfMissing(VirtualFile parent, String relative)` also requires writeAction — use this overload inside writeAction. Note: `createDirectoryIfMissing(String absolutePath)` self-acquires a write lock internally (safe to call outside writeAction, but DO NOT call it inside writeAction). Put the ENTIRE create-directory-and-write sequence inside a SINGLE writeAction block using the VirtualFile overload:

```kotlin
// ✓ CORRECT — everything that creates or modifies files goes INSIDE writeAction:
writeAction {
    val root = LocalFileSystem.getInstance().findFileByPath(project.basePath!!)!!
    val dir = VfsUtil.createDirectoryIfMissing(root, "src/main/java/com/example/model")  // ← writeAction required
    val f = dir.findChild("Product.java") ?: dir.createChildData(this, "Product.java")   // ← writeAction required
    VfsUtil.saveText(f, content)                                                          // ← writeAction required
}
// ✗ WRONG:
// val dir = VfsUtil.createDirectoryIfMissing(root, "src/main/java/...")  // OUTSIDE writeAction → throws!
// writeAction { VfsUtil.saveText(f, content) }                           // only saveText inside = WRONG
```
