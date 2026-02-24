
---

## Gradle Test Runner

PREFERRED over `ProcessBuilder("./gradlew")` inside exec_code — the latter spawns a nested Gradle daemon from within the IDE JVM, causing classpath conflicts:

```kotlin
import com.intellij.openapi.externalSystem.model.execution.ExternalSystemTaskExecutionSettings
import com.intellij.openapi.externalSystem.service.execution.ProgressExecutionMode
import com.intellij.openapi.externalSystem.task.TaskCallback
import com.intellij.openapi.externalSystem.util.ExternalSystemUtil
import org.jetbrains.plugins.gradle.util.GradleConstants
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout
import kotlin.time.Duration.Companion.minutes
val result = CompletableDeferred<Boolean>()
val s = com.intellij.openapi.externalSystem.model.execution.ExternalSystemTaskExecutionSettings()
s.externalProjectPath = project.basePath!!
// ⚠️ After writing new source files: add "--rerun-tasks" to force test execution even if UP-TO-DATE
s.taskNames = listOf(":api:test", "--tests", "shop.api.composite.product.ProductCompositeServiceApplicationTests", "--rerun-tasks")
s.externalSystemIdString = GradleConstants.SYSTEM_ID.toString()
ExternalSystemUtil.runTask(s, com.intellij.execution.executors.DefaultRunExecutor.EXECUTOR_ID,
    project, GradleConstants.SYSTEM_ID,
    object : TaskCallback { override fun onSuccess() { result.complete(true) }; override fun onFailure() { result.complete(false) } },
    ProgressExecutionMode.IN_BACKGROUND_ASYNC, false)
val ok = withTimeout(5.minutes) { result.await() }; println("Gradle result: success=$ok")
// When success=false: read JUnit XML test results directly for failure details:
val testResultsDir = findProjectFile("build/test-results/test")
testResultsDir?.children?.filter { it.name.endsWith(".xml") }?.forEach { xmlFile ->
    val content = com.intellij.openapi.vfs.VfsUtil.loadText(xmlFile)
    val failures = Regex("<failure[^>]*>(.+?)</failure>", RegexOption.DOT_MATCHES_ALL)
        .findAll(content).map { it.groupValues[1].take(300) }.toList()
    if (failures.isNotEmpty()) println("FAIL ${xmlFile.name}: " + failures.first())
    else println("PASS ${xmlFile.name}")
}
```
