import com.intellij.execution.RunManager
import com.intellij.execution.ProgramRunnerUtil
import com.intellij.execution.ExecutorRegistry
import com.intellij.execution.executors.DefaultRunExecutor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// Configuration - CUSTOMIZE THIS
val configurationName = "All Tests"  // Change to your test config name
val executorId = DefaultRunExecutor.EXECUTOR_ID  // or DefaultDebugExecutor.EXECUTOR_ID


// Find the run configuration
val manager = RunManager.getInstance(project)
val setting = manager.allSettings.firstOrNull { it.name == configurationName }

if (setting == null) {
    println("ERROR: Run configuration not found: $configurationName")
    println()
    println("Available configurations:")
    manager.allSettings.forEach { println("  • ${it.name}") }
    return
}

// Get executor
val executor = ExecutorRegistry.getInstance().getExecutorById(executorId)
if (executor == null) {
    println("ERROR: Executor not found: $executorId")
    return
}

// Execute on EDT (ProgramRunnerUtil returns void)
withContext(Dispatchers.EDT) {
    ProgramRunnerUtil.executeConfiguration(setting, executor)
}

// Print execution info
println("✓ Test execution started")
println()
println("Configuration: ${setting.name}")
println("Executor: ${executor.actionName}")
println()
println("Use 'wait-for-completion' example to poll for completion.")
println("Use 'inspect-test-results' example to access results after completion.")
