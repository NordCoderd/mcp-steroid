Debugger: Demo Debug Test (End-to-end)

Action-based demo that opens a test file, positions the caret, and fires the IDE debug action — works in both Rider (.NET) and IntelliJ (JUnit/TestNG/Kotlin).

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

```text
// In IntelliJ, use the DebugClass context action to debug tests.
// Works for JUnit 4/5, TestNG, and Kotlin Test — no manual config needed.

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
val testFile = LocalFileSystem.getInstance().refreshAndFindFileByPath(
    basePath + "/src/test/kotlin/com/jonnyzzz/mcpSteroid/ocr/DemoTestByJonnyzzz.kt"  // TODO: adjust path
) ?: error("Test file not found")
val editors = withContext(Dispatchers.EDT) {
    FileEditorManager.getInstance(project).openFile(testFile, true)
}
val textEditor = editors.filterIsInstance<TextEditor>().firstOrNull() ?: error("No text editor")
val editor = textEditor.editor
val text = editor.document.text
val classOffset = text.indexOf("class DemoTestByJonnyzzz")  // TODO: adjust class name
withContext(Dispatchers.EDT) { editor.caretModel.moveToOffset(classOffset) }

// Step 2: Debug test via IntelliJ context action (works for JUnit 4/5, TestNG, Kotlin Test)
val action = ActionManager.getInstance().getAction("DebugClass")
    ?: error("DebugClass not found")
withContext(Dispatchers.EDT) {
    val dataContext = DataManager.getInstance().getDataContext(editor.contentComponent)
    val presentation = action.templatePresentation.clone()
    val event = AnActionEvent.createEvent(dataContext, presentation, "EditorPopup", ActionUiKind.NONE, null)
    ActionUtil.performAction(action, event)
}
println("Debug test execution started")
```

For debugging Java/Kotlin tests in IntelliJ:
1. Set breakpoints using `mcp-steroid://debugger/set-line-breakpoint` (XDebuggerUtil works)
2. Open the test file, position caret on test class, fire `DebugClass` action
3. Wait for breakpoint hit using `mcp-steroid://debugger/wait-for-suspend`
4. Evaluate variables using `mcp-steroid://debugger/evaluate-expression`
5. Step through code using `mcp-steroid://debugger/step-over` (only if needed — skip if the bug is visible from evaluation at the breakpoint)
###_END_IF_###

# See also

Related test operations:
- [Inspect Test Results](mcp-steroid://test/inspect-test-results) - Access test results

Related IDE operations:
- [Run Configuration](mcp-steroid://ide/run-configuration) - List and execute run configs
