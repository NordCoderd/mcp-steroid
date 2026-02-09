import com.intellij.execution.RunManager
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.execution.ProgramRunnerUtil
import com.intellij.execution.ExecutorRegistry

// Configuration - modify these for your use case
val runConfigName = ""  // Leave empty to list only
val executorId = DefaultRunExecutor.EXECUTOR_ID
val dryRun = true


val manager = RunManager.getInstance(project)
val settings = manager.allSettings

println("Run Configurations (${settings.size}):")
settings.forEach { setting ->
    val typeName = setting.type.displayName
    println("  - ${setting.name} ($typeName)")
}

if (runConfigName.isBlank()) {
    println("Set runConfigName to execute a configuration.")
    return
}

val setting = settings.firstOrNull { it.name == runConfigName }
if (setting == null) {
    println("Run configuration not found: $runConfigName")
    return
}

val executor = ExecutorRegistry.getInstance().getExecutorById(executorId)
if (executor == null) {
    println("Executor not found: $executorId")
    return
}

if (dryRun) {
    println("Dry run: would execute '${setting.name}' with executor '$executorId'.")
    return
}

withContext(Dispatchers.EDT) {
    ProgramRunnerUtil.executeConfiguration(setting, executor)
}
println("Started run configuration: ${setting.name}")
