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


val configurationName = "intellij-mcp-steroids [test]"
val runManager = RunManager.getInstance(project)
val settings = runManager.allSettings.firstOrNull { it.name == configurationName }
    ?: error("Run configuration not found: $configurationName")

val executor = DefaultDebugExecutor.getDebugExecutorInstance()
withContext(Dispatchers.EDT) {
    ProgramRunnerUtil.executeConfiguration(settings, executor)
}

println("Started debug configuration:", settings.name)

/**
 * ## See Also
 *
 * Related debugger operations:
 * - [Set Line Breakpoint](mcp-steroid://debugger/set-line-breakpoint) - Create and manage breakpoints
 * - [Debug Session Control](mcp-steroid://debugger/debug-session-control) - Pause/resume/stop
 * - [List Threads](mcp-steroid://debugger/debug-list-threads) - Inspect execution stacks
 * - [Thread Dump](mcp-steroid://debugger/debug-thread-dump) - Generate thread dumps
 *
 * Related IDE operations:
 * - [Run Configuration](mcp-steroid://ide/run-configuration) - List and execute run configs
 *
 * Related test operations:
 * - [Run Tests](mcp-steroid://test/run-tests) - Execute test configuration
 * - [Wait for Completion](mcp-steroid://test/wait-for-completion) - Poll test status
 *
 * Overview resources:
 * - [Debugger Examples Overview](mcp-steroid://debugger/overview) - All debugger operations
 * - [Debugger Skill Guide](mcp-steroid://skill/debugger-guide) - Essential debugger knowledge
 */
