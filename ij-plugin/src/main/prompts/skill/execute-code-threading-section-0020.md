
Use `edtWriteAction { }` (a suspend wrapper) if you need suspend calls inside the write block.

---

## ⚠️ ALL VFS Mutation Ops Need writeAction — Not Just saveText

`createDirectoryIfMissing()`, `createChildData()`, `createChildFile()`, `createChildDirectory()`, `delete()`, `rename()`, `move()`, and `saveText()` ALL require `writeAction`. Calling any of these OUTSIDE a `writeAction` throws `Write access is allowed inside write-action only` at runtime. Always put the ENTIRE create-directory-and-write sequence inside a SINGLE `writeAction` block:

```kotlin
writeAction {
    val root = LocalFileSystem.getInstance().findFileByPath(project.basePath!!)!!
    val dir = VfsUtil.createDirectoryIfMissing(root, "src/main/java/com/example/model")  // ← needs writeAction
    val f = dir.findChild("Product.java") ?: dir.createChildData(this, "Product.java")   // ← needs writeAction
    VfsUtil.saveText(f, content)                                                          // ← needs writeAction
}
// ✗ WRONG: val dir = VfsUtil.createDirectoryIfMissing(...) OUTSIDE writeAction, then writeAction { saveText }
// ↑ This throws "Write access is allowed inside write-action only" on the createDirectoryIfMissing call
```
