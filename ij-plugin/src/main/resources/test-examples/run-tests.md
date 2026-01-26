/**
 * Test: Run Tests
 *
 * This example executes a test run configuration and returns
 * the RunContentDescriptor for further inspection.
 *
 * IntelliJ API used:
 * - RunManager - Access run configurations
 * - ProgramRunnerUtil - Execute configurations
 * - ExecutorRegistry - Get executor (Run or Debug)
 * - DefaultRunExecutor - Standard "Run" executor
 *
 * Parameters to customize:
 * - configurationName: Name of the run configuration to execute
 * - executorId: DefaultRunExecutor.EXECUTOR_ID or DefaultDebugExecutor.EXECUTOR_ID
 *
 * Output: Execution started confirmation with descriptor info
 */

import com.intellij.execution.RunManager
import com.intellij.execution.ProgramRunnerUtil
import com.intellij.execution.ExecutorRegistry
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.execution.ui.RunContentDescriptor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

execute {
    // Configuration - CUSTOMIZE THIS
    val configurationName = "All Tests"  // Change to your test config name
    val executorId = DefaultRunExecutor.EXECUTOR_ID  // or DefaultDebugExecutor.EXECUTOR_ID

    waitForSmartMode()

    // Find the run configuration
    val manager = RunManager.getInstance(project)
    val setting = manager.allSettings.firstOrNull { it.name == configurationName }

    if (setting == null) {
        println("ERROR: Run configuration not found: $configurationName")
        println()
        println("Available configurations:")
        manager.allSettings.forEach { println("  • ${it.name}") }
        return@execute
    }

    // Get executor
    val executor = ExecutorRegistry.getInstance().getExecutorById(executorId)
    if (executor == null) {
        println("ERROR: Executor not found: $executorId")
        return@execute
    }

    // Execute on EDT
    var descriptor: RunContentDescriptor? = null
    withContext(Dispatchers.EDT) {
        descriptor = ProgramRunnerUtil.executeConfiguration(setting, executor)
    }

    if (descriptor == null) {
        println("ERROR: Failed to start execution")
        return@execute
    }

    // Print execution info
    println("✓ Test execution started")
    println()
    println("Configuration: ${descriptor!!.displayName}")
    println("Executor: ${executor.actionName}")
    println("Process: ${descriptor!!.processHandler?.javaClass?.simpleName}")
    println()
    println("Use 'wait-for-completion' example to poll for completion.")
    println("Use 'inspect-test-results' example to access results after completion.")
}
