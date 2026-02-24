
### File Access Helpers

```kotlin
// Find VirtualFile by absolute path
fun findFile(path: String): VirtualFile?

// Find PsiFile by absolute path (suspend - uses readAction)
suspend fun findPsiFile(path: String): PsiFile?

// Find VirtualFile relative to project base path
fun findProjectFile(relativePath: String): VirtualFile?

// Find PsiFile relative to project base path (suspend - uses readAction)
suspend fun findProjectPsiFile(relativePath: String): PsiFile?
```
