/**
 * Test: Run Tests
 *
 * This example executes a test run configuration.
 * Use RunContentManager afterwards to locate the RunContentDescriptor.
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
 * Output: Execution started confirmation
 */

import com.intellij.execution.RunManager
import com.intellij.execution.ProgramRunnerUtil
import com.intellij.execution.ExecutorRegistry
import com.intellij.execution.executors.DefaultRunExecutor
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
}

/**
 * ## See Also
 *
 * Related test examples:
 * - [List Run Configurations](mcp-steroid://test/list-run-configurations) - Discover available tests
 * - [Wait for Completion](mcp-steroid://test/wait-for-completion) - Poll test status
 * - [Inspect Test Results](mcp-steroid://test/inspect-test-results) - Access results
 * - [Test Tree Navigation](mcp-steroid://test/test-tree-navigation) - Navigate test hierarchy
 *
 * Related IDE operations:
 * - [Run Configuration](mcp-steroid://ide/run-configuration) - List and execute run configs
 *
 * Related debugger operations:
 * - [Debug Run Configuration](mcp-steroid://debugger/debug-run-configuration) - Start debugging
 *
 * Overview resources:
 * - [Test Examples Overview](mcp-steroid://test/overview) - Complete test execution guide
 * - [Test Runner Skill Guide](mcp-steroid://skill/test-runner-guide) - Essential test knowledge
 */
