
### VFS Path Conflict Resolution (file exists where directory expected)

When VFS reports `'path/security' is not a directory`, a plain file occupies a path you expect to be a directory. Fix by checking `isDirectory`, deleting the blocking file, then recreating the directory:

```kotlin
// Safe directory creation — handles file/directory conflict
writeAction {
    val parent = LocalFileSystem.getInstance()
        .findFileByPath("$basePath/src/main/java/eval/sample")
        ?: error("Parent not found")
    var dir = parent.findChild("security")
    if (dir != null && !dir.isDirectory) {
        dir.delete(this)  // remove blocking file
        dir = null
    }
    dir ?: parent.createChildDirectory(this, "security")
}
```
