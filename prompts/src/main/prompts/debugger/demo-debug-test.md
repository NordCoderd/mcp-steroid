Debugger: Demo Debug Test (End-to-end)

Launches a debug test session via context action: open test file, position caret on test class/method, fire debug action. Falls back to JUnitConfiguration in IntelliJ if needed.

###_IF_IDE[RD]_###

```kotlin
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

```kotlin
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

// Step 1: Open test file and position caret ON the method name
val basePath = project.basePath ?: error("No basePath")
val testFile = LocalFileSystem.getInstance().refreshAndFindFileByPath(basePath + "/src/test/kotlin/com/example/MyTest.kt")
    ?: error("Test file not found")
val editors = withContext(Dispatchers.EDT) {
    FileEditorManager.getInstance(project).openFile(testFile, true)
}
val textEditor = editors.filterIsInstance<TextEditor>().firstOrNull() ?: error("No text editor")
val editor = textEditor.editor
val text = editor.document.text
// IMPORTANT: caret must be on the NAME identifier, not on the keyword before it.
// Placing caret on 'fun' or 'class' keyword triggers a "nothing here" popup.
// - Caret on method name  → debugs that single test method
// - Caret on class name   → debugs all tests in the class
// Advance past "fun " (or "class ") to land on the identifier.
val caretOffset = run {
    val funIdx = text.indexOf("fun myTestMethod")
    if (funIdx >= 0) funIdx + "fun ".length
    else text.indexOf("class MyTest") + "class ".length
}
withContext(Dispatchers.EDT) { editor.caretModel.moveToOffset(caretOffset) }

// Step 2: Debug via context action — "Debug 'myTestMethod'" appears in gutter/context menu
// Action ID: "DebugClass" (shortcut shown in gutter icon tooltip)
val action = ActionManager.getInstance().getAction("DebugClass")
    ?: error("DebugClass action not found")
withContext(Dispatchers.EDT) {
    val dataContext = DataManager.getInstance().getDataContext(editor.contentComponent)
    val presentation = action.templatePresentation.clone()
    val event = AnActionEvent.createEvent(dataContext, presentation, "EditorPopup", ActionUiKind.NONE, null)
    ActionUtil.performAction(action, event)
}
println("Debug context action fired — check XDebuggerManager for active session")
```

For debugging Java/Kotlin tests in IntelliJ:
1. Set breakpoints using `mcp-steroid://debugger/set-line-breakpoint` (XDebuggerUtil works)
2. Open test file, position caret ON the method name (to debug one test) or ON the class name (to debug all tests in the class) — caret must be on the identifier, not on the `fun`/`class` keyword, or you'll get "nothing here". Fire `DebugContextAction`
3. If no session starts, create a `JUnitConfiguration` programmatically (set `MAIN_CLASS_NAME`, `TEST_OBJECT = TEST_CLASS`, and assign the module via `ModuleManager`) — see `mcp-steroid://ide/run-configuration` for the pattern
4. Wait for breakpoint hit using `mcp-steroid://debugger/wait-for-suspend`
5. Evaluate variables using `mcp-steroid://debugger/evaluate-expression`
6. Step through code using `mcp-steroid://debugger/step-over` (only if needed — skip if the bug is visible from evaluation at the breakpoint)
###_END_IF_###

# See also

Related test operations:
- [Inspect Test Results](mcp-steroid://test/inspect-test-results) - Access test results

Related IDE operations:
- [Run Configuration](mcp-steroid://ide/run-configuration) - List and execute run configs
