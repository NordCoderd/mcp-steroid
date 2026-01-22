/**
 * Debug Run Configuration
 *
 * Start an existing run configuration in Debug mode using
 * DefaultDebugExecutor and ProgramRunnerUtil.
 *
 * Parameters:
 * - configurationName: name of an existing run configuration.
 *
 * IntelliJ APIs: RunManager, ProgramRunnerUtil, DefaultDebugExecutor
 */
import com.intellij.execution.ProgramRunnerUtil
import com.intellij.execution.RunManager
import com.intellij.execution.executors.DefaultDebugExecutor
import com.intellij.openapi.application.EDT
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

execute {
    waitForSmartMode()

    val configurationName = "intellij-mcp-steroids [test]"
    val runManager = RunManager.getInstance(project)
    val settings = runManager.allSettings.firstOrNull { it.name == configurationName }
        ?: error("Run configuration not found: $configurationName")

    val executor = DefaultDebugExecutor.getDebugExecutorInstance()
    withContext(Dispatchers.EDT) {
        ProgramRunnerUtil.executeConfiguration(settings, executor)
    }

    println("Started debug configuration:", settings.name)
}
