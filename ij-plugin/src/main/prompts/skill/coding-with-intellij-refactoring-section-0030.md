## Quick Reference Card

### Context Properties

```kotlin
project: Project         // Current project
params: JsonElement      // Execution parameters
disposable: Disposable   // For cleanup
isDisposed: Boolean      // Check if disposed
```

### Output

```kotlin
println(vararg values)                 // Print values
printJson(obj)                         // JSON output
printException(msg, throwable)         // Error with stack trace
progress(msg)                          // Progress (throttled)
takeIdeScreenshot(fileName)            // Capture screenshot
```

### Read/Write (No imports needed!)

```kotlin
readAction { }           // Read PSI/VFS
writeAction { }          // Write PSI/VFS
smartReadAction { }      // Wait + read
```

### Scopes (No imports needed!)

```kotlin
projectScope()           // Project files only
allScope()               // Project + libraries
```

### File Access

```kotlin
findFile(path)                    // VirtualFile by absolute path
findPsiFile(path)                 // PsiFile by absolute path
findProjectFile(relativePath)     // VirtualFile by project-relative path
findProjectPsiFile(relativePath)  // PsiFile by project-relative path
```

### Code Analysis (Recommended)

```kotlin
runInspectionsDirectly(file, includeInfoSeverity = false)
```

### Common Imports

```kotlin
// PSI
import com.intellij.psi.PsiManager
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.search.searches.ReferencesSearch

// Java PSI
import com.intellij.psi.JavaPsiFacade

// Kotlin PSI
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.idea.stubindex.KotlinClassShortNameIndex

// VFS
import com.intellij.openapi.vfs.LocalFileSystem

// Editor
import com.intellij.openapi.fileEditor.FileEditorManager

// Commands
import com.intellij.openapi.command.WriteCommandAction
```

### Thread Safety

```kotlin
readAction { }    // For reading PSI/VFS
writeAction { }   // For writing PSI/VFS
smartReadAction { } // Wait for indexing + read
```

### Example: Find and Modify
