import com.intellij.execution.ProgramRunnerUtil
import com.intellij.execution.RunManager
import com.intellij.execution.executors.DefaultDebugExecutor

val configurationName = "mcp-steroid [test]"  // TODO: Set your run configuration name
val runManager = RunManager.getInstance(project)
val settings = runManager.allSettings.firstOrNull { it.name == configurationName }
    ?: error("Run configuration not found: $configurationName")

val executor = DefaultDebugExecutor.getDebugExecutorInstance()
// IntelliJ 253 package: com.intellij.execution.ProgramRunnerUtil
withContext(Dispatchers.EDT) {
    ProgramRunnerUtil.executeConfiguration(settings, executor)
}

println("Started debug configuration:", settings.name)
