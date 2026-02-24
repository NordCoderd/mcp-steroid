# Execute Code: Testing Patterns

## ⚡ Incremental Compile Check After Bulk File Creation (~10s vs ~90s for mvnw test-compile)

After creating multiple files in a batch `writeAction`, run a project-wide incremental compile to catch errors fast — before paying for a full Gradle/Maven test cycle:

```kotlin
import com.intellij.task.ProjectTaskManager
val result = ProjectTaskManager.getInstance(project).buildAllModules().get()
println("Compile errors: ${result.hasErrors()}, warnings: ${result.hasWarnings()}")
// If hasErrors() == true: read and fix compile errors BEFORE running tests.
// This saves a full Gradle/Maven compile round-trip (~60-90s saved per error).
```
