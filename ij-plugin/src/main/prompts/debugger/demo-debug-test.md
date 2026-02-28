Debugger: Demo Debug Test (End-to-end)

End-to-end demo that runs a test in Debug, sets breakpoints, evaluates variables, and prints test results.

###_IF_RIDER_###

```text
// In Rider, use RiderUnitTestDebugContextAction to debug tests.
// JUnitConfiguration does NOT exist in Rider.

import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.ActionUiKind
import com.intellij.openapi.actionSystem.ex.ActionUtil
import com.intellij.ide.DataManager

// Step 1: Open test file and position caret on test class
val basePath = project.basePath ?: error("No basePath")
val testFile = LocalFileSystem.getInstance().refreshAndFindFileByPath(basePath + "/DemoRider.Tests/LeaderboardTests.cs")
    ?: error("Test file not found")
val editors = withContext(Dispatchers.EDT) {
    FileEditorManager.getInstance(project).openFile(testFile, true)
}
val textEditor = editors.filterIsInstance<TextEditor>().firstOrNull() ?: error("No text editor")
val editor = textEditor.editor
val text = editor.document.text
val classOffset = text.indexOf("class LeaderboardTests")
withContext(Dispatchers.EDT) { editor.caretModel.moveToOffset(classOffset) }

// Step 2: Debug tests via native Rider action
val action = ActionManager.getInstance().getAction("RiderUnitTestDebugContextAction")
    ?: error("RiderUnitTestDebugContextAction not found")
withContext(Dispatchers.EDT) {
    val dataContext = DataManager.getInstance().getDataContext(editor.contentComponent)
    val presentation = action.templatePresentation.clone()
    val event = AnActionEvent.createEvent(dataContext, presentation, "EditorPopup", ActionUiKind.NONE, null)
    ActionUtil.performAction(action, event)
}
println("Debug test execution started")
```

For debugging .NET code in Rider:
1. Set breakpoints using `mcp-steroid://debugger/set-line-breakpoint` (XDebuggerUtil works in Rider)
2. Open the test file, position caret on test class, fire `RiderUnitTestDebugContextAction`
3. Wait for breakpoint hit using `mcp-steroid://debugger/wait-for-suspend`
4. Evaluate variables using `mcp-steroid://debugger/evaluate-expression`
5. Step through code using `mcp-steroid://debugger/step-over` (only if needed — skip if the bug is visible from evaluation at the breakpoint)
###_ELSE_###
End-to-end demo that configures a JUnit test, runs it in Debug, resumes if paused, waits for completion, and prints test results.

```kotlin
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

val configurationName = "DemoTestByJonnyzzz (Debug)"  // TODO: Set your run configuration name
val testClass = "com.jonnyzzz.mcpSteroid.ocr.DemoTestByJonnyzzz"  // TODO: Set your test class FQN

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
    return
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

val handler = descriptor.processHandler
if (handler == null) {
    println("No process handler available")
    return
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
    return
}

val console = descriptor.executionConsole as? SMTRunnerConsoleView
if (console == null) {
    println("Not a test execution or results not available")
    return
}

val results = console.resultsViewer
println("Tests status: ${results.getTestsStatus()}")
println(
    "Counts: total=${results.getTotalTestCount()} started=${results.getStartedTestCount()} " +
        "finished=${results.getFinishedTestCount()} failed=${results.getFailedTestCount()} " +
        "ignored=${results.getIgnoredTestCount()}"
)

val root = results.testsRootNode
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

println("Note: DemoTestByJonnyzzz is intentionally broken; failure is expected.")
```
###_END_IF_###

# See also

Related test operations:
- [Inspect Test Results](mcp-steroid://test/inspect-test-results) - Access test results

Related IDE operations:
- [Run Configuration](mcp-steroid://ide/run-configuration) - List and execute run configs
