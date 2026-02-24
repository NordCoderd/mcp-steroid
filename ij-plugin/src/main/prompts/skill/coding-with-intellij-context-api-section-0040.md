
**Important**: These are **built-in** - you do NOT need to import `readAction` or `writeAction` from IntelliJ Platform!

### Built-in Search Scopes (NO IMPORTS NEEDED!)

```kotlin
// Project files only (no libraries)
fun projectScope(): GlobalSearchScope

// Project + libraries
fun allScope(): GlobalSearchScope
```
