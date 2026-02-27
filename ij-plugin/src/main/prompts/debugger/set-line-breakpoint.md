Set Line Breakpoint

Create a line breakpoint for a file/line in the current project.

```kotlin
import com.intellij.xdebugger.breakpoints.XLineBreakpoint
import com.intellij.xdebugger.XDebuggerManager
import com.intellij.xdebugger.XDebuggerUtil
import java.nio.file.Paths

val filePath = "src/main/kotlin/com/jonnyzzz/mcpSteroid/execution/ExecutionManager.kt"
val lineNumberInEditor = 49  // TODO: Set your value

val projectRoot = project.basePath ?: error("Project basePath is null")
val absolutePath = Paths.get(projectRoot, filePath).toString()
val virtualFile = LocalFileSystem.getInstance().findFileByPath(absolutePath)
    ?: error("File not found: $absolutePath")

val breakpointManager = XDebuggerManager.getInstance(project).breakpointManager
val lineIndex = lineNumberInEditor - 1

// Keep the script idempotent for repeated runs.
writeAction {
    breakpointManager.allBreakpoints
        .filterIsInstance<XLineBreakpoint<*>>()
        .filter { it.fileUrl == virtualFile.url && it.line == lineIndex }
        .forEach { breakpointManager.removeBreakpoint(it) }
}

// Prefer toggleLineBreakpoint over addLineBreakpoint(..., null): it picks proper type/properties.
withContext(Dispatchers.EDT) {
    XDebuggerUtil.getInstance().toggleLineBreakpoint(project, virtualFile, lineIndex)
}

// Verify: poll briefly — in Rider, breakpoint creation is async (RD protocol to backend)
var breakpoint: com.intellij.xdebugger.breakpoints.XBreakpoint<*>? = null
repeat(10) {
    breakpoint = breakpointManager.allBreakpoints
        .filterIsInstance<XLineBreakpoint<*>>()
        .firstOrNull { it.fileUrl == virtualFile.url && it.line == lineIndex }
    if (breakpoint != null) return@repeat
    delay(200)
}

if (breakpoint != null) {
    println("Created breakpoint:", breakpoint)
} else {
    println("WARNING: Breakpoint not confirmed for line $lineNumberInEditor (may still be pending)")
    println("Existing breakpoints:")
    breakpointManager.allBreakpoints.forEach { println("  ${it.javaClass.simpleName}: ${it}") }
}
```

# See also

Related IDE operations:
- [Run Configuration](mcp-steroid://ide/run-configuration) - List and execute run configs

Related test operations:
- [Run Tests](mcp-steroid://test/run-tests) - Execute test configuration
- [Wait for Completion](mcp-steroid://test/wait-for-completion) - Poll test status

Overview resources:
- [Debugger Skill Guide](mcp-steroid://skill/debugger-skill) - Essential debugger knowledge
