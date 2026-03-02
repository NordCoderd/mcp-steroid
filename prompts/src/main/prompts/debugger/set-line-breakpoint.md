Set Line Breakpoint

Toggle or remove a line breakpoint for a file/line in the current project.

## Toggle breakpoint

Toggles a breakpoint using `toggleLineBreakpoint` on EDT — adds if absent, removes if present.
`toggleLineBreakpoint` MUST run on `Dispatchers.EDT`.
For idempotent "ensure breakpoint exists" (never removes), use `add-breakpoint.md` instead.

```kotlin
import com.intellij.xdebugger.XDebuggerUtil
import java.nio.file.Paths

val filePath = "src/main/kotlin/com/example/MyClass.kt"
val lineNumberInEditor = 49  // TODO: Set your value

val projectRoot = project.basePath ?: error("Project basePath is null")
val absolutePath = Paths.get(projectRoot, filePath).toString()
val virtualFile = LocalFileSystem.getInstance().findFileByPath(absolutePath)
    ?: error("File not found: $absolutePath")

val lineIndex = lineNumberInEditor - 1  // API is 0-indexed

// toggleLineBreakpoint MUST run on Dispatchers.EDT.
// It ADDS if absent, REMOVES if present (toggle semantics).
// WARNING: If a breakpoint already exists, toggleLineBreakpoint will REMOVE it.
// For idempotent "ensure breakpoint exists", use add-breakpoint.md instead.
withContext(Dispatchers.EDT) {
    XDebuggerUtil.getInstance().toggleLineBreakpoint(project, virtualFile, lineIndex)
}
println("Toggled breakpoint at $filePath:$lineNumberInEditor")
```

## Remove breakpoint

```kotlin
import com.intellij.xdebugger.XDebuggerManager
import com.intellij.xdebugger.XDebuggerUtil
import com.intellij.xdebugger.breakpoints.XLineBreakpoint
import com.intellij.xdebugger.breakpoints.XLineBreakpointType
import java.nio.file.Paths

val filePath = "src/main/kotlin/com/example/MyClass.kt"
val lineNumberInEditor = 49  // TODO: Set your value

val projectRoot = project.basePath ?: error("Project basePath is null")
val absolutePath = Paths.get(projectRoot, filePath).toString()
val virtualFile = LocalFileSystem.getInstance().findFileByPath(absolutePath)
    ?: error("File not found: $absolutePath")

val breakpointManager = XDebuggerManager.getInstance(project).breakpointManager
val lineIndex = lineNumberInEditor - 1

// Find and remove all breakpoints at this line
val removed = breakpointManager.allBreakpoints
    .filterIsInstance<XLineBreakpoint<*>>()
    .filter { it.fileUrl == virtualFile.url && it.line == lineIndex }

if (removed.isEmpty()) {
    println("No breakpoint at $filePath:$lineNumberInEditor")
} else {
    removed.forEach { breakpointManager.removeBreakpoint(it) }
    println("Removed ${removed.size} breakpoint(s) at $filePath:$lineNumberInEditor")
}
```

## Important notes

```kotlin
// Important notes:
//
// toggleLineBreakpoint() is a TOGGLE — it adds if absent, REMOVES if present.
// It MUST run on Dispatchers.EDT.
//
// For idempotent "ensure breakpoint exists" (never removes an existing one),
// use add-breakpoint.md which uses findBreakpointsAtLine + addLineBreakpoint.
//
// - Line numbers are 0-indexed in the API (editor line 7 = API line 6)
// - In Rider, breakpoint creation is async (RD protocol to backend)
println("See code blocks above for toggle and remove breakpoint patterns")
```

# See also

Related debugger operations:
- [Add Breakpoint](mcp-steroid://debugger/add-breakpoint) - Idempotent add (never removes existing)
- [Remove Breakpoint](mcp-steroid://debugger/remove-breakpoint) - Remove breakpoints from a line
- [Debug Run Configuration](mcp-steroid://debugger/debug-run-configuration) - Start debug session

Overview resources:
- [Debugger Skill Guide](mcp-steroid://prompt/debugger-skill) - Essential debugger knowledge
- [Debugger Overview](mcp-steroid://debugger/overview) - All debugger examples
