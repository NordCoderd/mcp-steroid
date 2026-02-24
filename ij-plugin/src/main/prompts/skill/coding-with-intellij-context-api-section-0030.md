
### Built-in Read/Write Actions (NO IMPORTS NEEDED!)

```kotlin
// Execute under read lock (for PSI/VFS reads)
suspend fun <T> readAction(block: () -> T): T

// Execute under write lock (for PSI/VFS writes)
suspend fun <T> writeAction(block: () -> T): T

// Wait for smart mode + read action in one call
suspend fun <T> smartReadAction(block: () -> T): T
```
