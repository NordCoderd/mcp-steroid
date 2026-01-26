/**
 * Test: Demo Debug Test (End-to-end)
 *
 * This example creates/updates the demo JUnit configuration, starts it in Debug mode, resumes the debugger if it pauses, waits for completion, and prints test results.
 *
 * IntelliJ API used:
 * - RunManager, JUnitConfiguration
 * - ProgramRunnerUtil, ExecutorRegistry, DefaultDebugExecutor
 * - XDebuggerManager
 * - RunContentManager, SMTRunnerConsoleView
 *
 * Expected: DemoTestByJonnyzzz fails (intentional)
 * Cleanup:
 * - When you no longer need debugging, stop sessions on EDT:
 *   withContext(Dispatchers.EDT) { XDebuggerManager.getInstance(project).debugSessions.forEach { it.stop() } }
 */

import com.intellij.execution.ExecutorRegistry
import com.intellij.execution.ProgramRunnerUtil
import com.intellij.execution.RunManager
import com.intellij.execution.executors.DefaultDebugExecutor
import com.intellij.execution.junit.JUnitConfiguration
import com.intellij.execution.junit.JUnitConfigurationType
import com.intellij.execution.testframework.AbstractTestProxy
import com.intellij.execution.testframework.sm.runner.ui.SMTRunnerConsoleView
import com.intellij.execution.ui.RunContentManager
import com.intellij.openapi.module.ModuleManager
import com.intellij.xdebugger.XDebuggerManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

execute {
    waitForSmartMode()

    val configurationName = "DemoTestByJonnyzzz (Debug)"
    val testClass = "com.jonnyzzz.intellij.mcp.ocr.DemoTestByJonnyzzz"

    val runManager = RunManager.getInstance(project)
    val settings = runManager.allSettings.firstOrNull { it.name == configurationName }
        ?: runManager.createConfiguration(
            configurationName,
            JUnitConfigurationType.getInstance().configurationFactories.first()
        ).also { runManager.addConfiguration(it) }

    val junitConfig = settings.configuration as? JUnitConfiguration
        ?: error("Run configuration is not JUnit: ${settings.configuration.javaClass.name}")

    val data = junitConfig.persistentData
    data.TEST_OBJECT = JUnitConfiguration.TEST_CLASS
    data.MAIN_CLASS_NAME = testClass
    junitConfig.setVMParameters("-ea -Dmcp.demo.by.jonnyzzz=true")

    val modules = ModuleManager.getInstance(project).modules.toList()
    val module = modules.firstOrNull { it.name.endsWith(".test") }
        ?: modules.firstOrNull { it.name.contains("test", ignoreCase = true) }
        ?: modules.firstOrNull()
    if (module != null) {
        junitConfig.setModule(module)
    }

    runManager.selectedConfiguration = settings

    val executor = ExecutorRegistry.getInstance().getExecutorById(DefaultDebugExecutor.EXECUTOR_ID)
        ?: error("Debug executor not found")

    withContext(Dispatchers.EDT) {
        ProgramRunnerUtil.executeConfiguration(settings, executor)
    }

    println("Started debug run: ${settings.name}")

    val contentManager = RunContentManager.getInstance(project)
    var descriptor = contentManager.allDescriptors.lastOrNull { it.displayName == settings.name }
    repeat(40) {
        if (descriptor != null) return@repeat
        delay(250)
        descriptor = contentManager.allDescriptors.lastOrNull { it.displayName == settings.name }
    }

    if (descriptor == null) {
        println("RunContentDescriptor not found. Try again after the run starts.")
        return@execute
    }

    val debugger = XDebuggerManager.getInstance(project)
    withContext(Dispatchers.EDT) {
        debugger.debugSessions
            .filter { it.sessionName == settings.name && it.isPaused && !it.isStopped }
            .forEach { session ->
                println("Resuming session: ${session.sessionName}")
                session.resume()
            }
    }

    val handler = descriptor!!.processHandler
    if (handler == null) {
        println("No process handler available")
        return@execute
    }

    if (!handler.isProcessTerminated) {
        repeat(60) {
            if (handler.isProcessTerminated) return@repeat
            delay(250)
        }
    }

    println("Process terminated: ${handler.isProcessTerminated} exitCode=${handler.exitCode}")
    if (!handler.isProcessTerminated) {
        println("Still running. Re-run this script to inspect final results.")
        return@execute
    }

    val console = descriptor!!.executionConsole as? SMTRunnerConsoleView
    if (console == null) {
        println("Not a test execution or results not available")
        return@execute
    }

    val results = console.resultsViewer
    println("Tests status: ${results.getTestsStatus()}")
    println(
        "Counts: total=${results.getTotalTestCount()} started=${results.getStartedTestCount()} " +
            "finished=${results.getFinishedTestCount()} failed=${results.getFailedTestCount()} " +
            "ignored=${results.getIgnoredTestCount()}"
    )

    val root = results.testsRootNode
    if (root != null) {
        fun status(proxy: AbstractTestProxy): String = when {
            proxy.isPassed -> "PASSED"
            proxy.isIgnored -> "IGNORED"
            proxy.isDefect -> "FAILED"
            proxy.isInProgress -> "RUNNING"
            else -> "UNKNOWN"
        }

        println("Root: ${root.name} [${status(root)}]")
        root.children.forEach { child ->
            println("- ${child.name} [${status(child)}]")
        }
    }

    println("Note: DemoTestByJonnyzzz is intentionally broken; failure is expected.")
}

/**
 * ## See Also
 *
 * Related test operations:
 * - [Run Tests](mcp-steroid://test/run-tests) - Execute test configuration
 * - [Wait for Completion](mcp-steroid://test/wait-for-completion) - Poll test status
 * - [Inspect Test Results](mcp-steroid://test/inspect-test-results) - Access test results
 * 
 * Related debugger operations:
 * - [Debug Run Configuration](mcp-steroid://debugger/debug-run-configuration) - Start debugging
 * - [Debug Session Control](mcp-steroid://debugger/debug-session-control) - Pause/resume/stop
 * 
 * Related IDE operations:
 * - [Run Configuration](mcp-steroid://ide/run-configuration) - List and execute run configs
 * 
 * Overview resources:
 * - [Test Examples Overview](mcp-steroid://test/overview) - All test workflows
 * - [Debugger Examples Overview](mcp-steroid://debugger/overview) - Debugger workflows
 */
