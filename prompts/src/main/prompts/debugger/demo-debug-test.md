Debugger: Demo Debug Test (End-to-end)

Launches a debug test session via context action: open test file, position caret on test class/method, fire debug action. Falls back to JUnitConfiguration in IntelliJ if needed.

###_IF_IDE[RD]_###

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

```text
// In IntelliJ, use the same action-based approach as Rider: open the test file,
// position the caret on the test class or method, then fire the debug context action.
// If no debug session starts within a few seconds (check XDebuggerManager.getInstance(project).currentSession),
// use the JUnitConfiguration fallback below.

import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.ActionUiKind
import com.intellij.openapi.actionSystem.ex.ActionUtil
import com.intellij.ide.DataManager

// Step 1: Open test file and position caret on test class or method
val basePath = project.basePath ?: error("No basePath")
val testFile = LocalFileSystem.getInstance().refreshAndFindFileByPath(basePath + "/src/test/kotlin/com/example/MyTest.kt")
    ?: error("Test file not found")
val editors = withContext(Dispatchers.EDT) {
    FileEditorManager.getInstance(project).openFile(testFile, true)
}
val textEditor = editors.filterIsInstance<TextEditor>().firstOrNull() ?: error("No text editor")
val editor = textEditor.editor
val text = editor.document.text
// Prefer a specific test method offset over the class offset for less ambiguity
val caretOffset = text.indexOf("fun myTestMethod").takeIf { it >= 0 }
    ?: text.indexOf("class MyTest")
withContext(Dispatchers.EDT) { editor.caretModel.moveToOffset(caretOffset) }

// Step 2: Debug via context action (same pattern as Rider)
val action = ActionManager.getInstance().getAction("DebugContextAction")
    ?: error("DebugContextAction not found")
withContext(Dispatchers.EDT) {
    val dataContext = DataManager.getInstance().getDataContext(editor.contentComponent)
    val presentation = action.templatePresentation.clone()
    val event = AnActionEvent.createEvent(dataContext, presentation, "EditorPopup", ActionUiKind.NONE, null)
    ActionUtil.performAction(action, event)
}
println("Debug context action fired — check XDebuggerManager for active session")

// --- FALLBACK: JUnitConfiguration (use if action-based approach produces no session) ---
// import com.intellij.execution.ProgramRunnerUtil
// import com.intellij.execution.RunManager
// import com.intellij.execution.executors.DefaultDebugExecutor
// import com.intellij.execution.junit.JUnitConfiguration
// import com.intellij.execution.junit.JUnitConfigurationType
// import com.intellij.openapi.module.ModuleManager
//
// val configurationName = "MyTest (Debug)"           // TODO: set your run configuration name
// val testClass = "com.example.MyTest"               // TODO: set your test class FQN
// val runManager = RunManager.getInstance(project)
// val settings = runManager.allSettings.firstOrNull { it.name == configurationName }
//     ?: runManager.createConfiguration(configurationName,
//         JUnitConfigurationType.getInstance().configurationFactories.first()
//     ).also { runManager.addConfiguration(it) }
// val junitConfig = settings.configuration as JUnitConfiguration
// val data = junitConfig.persistentData
// data.TEST_OBJECT = JUnitConfiguration.TEST_CLASS
// data.MAIN_CLASS_NAME = testClass
// // CRITICAL: set the module so IntelliJ can find the test class
// val modules = ModuleManager.getInstance(project).modules.toList()
// val module = modules.firstOrNull { it.name.endsWith(".test") }
//     ?: modules.firstOrNull { it.name.contains("test", ignoreCase = true) }
//     ?: modules.firstOrNull()
// if (module != null) junitConfig.setModule(module)
// runManager.selectedConfiguration = settings
// withContext(Dispatchers.EDT) {
//     ProgramRunnerUtil.executeConfiguration(settings, DefaultDebugExecutor.getDebugExecutorInstance())
// }
// println("Started debug run: ${settings.name}")
```

For debugging Java/Kotlin tests in IntelliJ:
1. Set breakpoints using `mcp-steroid://debugger/set-line-breakpoint` (XDebuggerUtil works)
2. Open test file, position caret on test class or method, fire `DebugContextAction`
3. If no session starts, use the JUnitConfiguration fallback (uncomment the block above)
4. Wait for breakpoint hit using `mcp-steroid://debugger/wait-for-suspend`
5. Evaluate variables using `mcp-steroid://debugger/evaluate-expression`
6. Step through code using `mcp-steroid://debugger/step-over` (only if needed — skip if the bug is visible from evaluation at the breakpoint)
###_END_IF_###

# See also

Related test operations:
- [Inspect Test Results](mcp-steroid://test/inspect-test-results) - Access test results

Related IDE operations:
- [Run Configuration](mcp-steroid://ide/run-configuration) - List and execute run configs
