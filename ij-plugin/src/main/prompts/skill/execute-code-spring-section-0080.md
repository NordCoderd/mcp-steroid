
---

## ⚠️ Multi-Module Gradle: Run Tests in the Correct Subproject

`./gradlew test --tests ClassName` silently finds NO tests when the class is in a submodule.
ALWAYS use the subproject prefix and `--rerun-tasks` after writing new files:

```kotlin
// Find the correct Gradle subproject path from module content roots:
import com.intellij.openapi.roots.ProjectRootManager
val roots = readAction { ProjectRootManager.getInstance(project).contentSourceRoots }
roots.forEach { println(it.path) }
// Identify which path contains your test class, then use that subproject in the task name.

// Run tests in the correct submodule via IDE (respects project SDK, reuses Gradle daemon):
import com.intellij.openapi.externalSystem.model.execution.ExternalSystemTaskExecutionSettings
import com.intellij.openapi.externalSystem.service.execution.ProgressExecutionMode
import com.intellij.openapi.externalSystem.task.TaskCallback
import com.intellij.openapi.externalSystem.util.ExternalSystemUtil
import org.jetbrains.plugins.gradle.util.GradleConstants
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout
import kotlin.time.Duration.Companion.minutes

val result = CompletableDeferred<Boolean>()
val s = ExternalSystemTaskExecutionSettings()
s.externalProjectPath = project.basePath!!
// ⚠️ Include subproject prefix AND --rerun-tasks to prevent UP-TO-DATE skip after file creation:
s.taskNames = listOf(":microservices:product-composite-service:test",
    "--tests", "shop.microservices.composite.product.ProductCompositeApiTests",
    "--rerun-tasks", "--no-daemon")
s.externalSystemIdString = GradleConstants.SYSTEM_ID.toString()
ExternalSystemUtil.runTask(s, com.intellij.execution.executors.DefaultRunExecutor.EXECUTOR_ID,
    project, GradleConstants.SYSTEM_ID,
    object : TaskCallback {
        override fun onSuccess() { result.complete(true) }
        override fun onFailure() { result.complete(false) }
    },
    ProgressExecutionMode.IN_BACKGROUND_ASYNC, false)
val ok = withTimeout(5.minutes) { result.await() }
println("Gradle result: success=$ok")
// If success=false: read JUnit XML in build/test-results/ for failure details.
```
