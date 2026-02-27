Set Line Breakpoint

Create or remove a line breakpoint for a file/line in the current project.

## Add breakpoint (idempotent)

Uses `findBreakpointsAtLine` + `addLineBreakpoint` to ensure exactly one breakpoint exists.
Do NOT use `toggleLineBreakpoint` for "ensure breakpoint exists" — it removes an existing breakpoint
if one is already present (toggle semantics).

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
val lineIndex = lineNumberInEditor - 1  // API is 0-indexed

// Find the correct breakpoint type for this file+line (Java, Kotlin, C#, etc.)
val breakpointType = readAction {
    XDebuggerUtil.getInstance().lineBreakpointTypes
        .firstOrNull { it.canPutAt(virtualFile, lineIndex, project) }
} ?: error("No breakpoint type available for $filePath:$lineNumberInEditor")

// Check if breakpoint already exists (idempotent — safe to call repeatedly)
@Suppress("UNCHECKED_CAST")
val bpType = breakpointType as XLineBreakpointType<Nothing?>
val existing = breakpointManager.findBreakpointsAtLine(bpType, virtualFile, lineIndex)
if (existing.isNotEmpty()) {
    println("Breakpoint already exists at $filePath:$lineNumberInEditor")
    println("Breakpoint:", existing.first())
} else {
    // Create breakpoint properties and add it
    val properties = readAction { breakpointType.createBreakpointProperties(virtualFile, lineIndex) }
    val breakpoint = breakpointManager.addLineBreakpoint(bpType, virtualFile.url, lineIndex, properties)
    println("Created breakpoint at $filePath:$lineNumberInEditor")
    println("Breakpoint:", breakpoint)
}
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

```text
WARNING: toggleLineBreakpoint() is a TOGGLE — it adds if absent, REMOVES if present.
Never use it for "ensure breakpoint exists". Use the idempotent pattern above instead.

- Line numbers are 0-indexed in the API (editor line 7 = API line 6)
- fileUrl is VirtualFile.getUrl() format (e.g., "file:///path/to/File.java")
- addLineBreakpoint does NOT deduplicate — calling it twice creates two breakpoints
- Always use findBreakpointsAtLine first to check for existing breakpoints
- In Rider, breakpoint creation is async (RD protocol to backend) — the breakpoint
  object is returned immediately but may take a moment to become fully active
```

# See also

Related IDE operations:
- [Run Configuration](mcp-steroid://ide/run-configuration) - List and execute run configs

Related test operations:
- [Run Tests](mcp-steroid://test/run-tests) - Execute test configuration
- [Wait for Completion](mcp-steroid://test/wait-for-completion) - Poll test status

Overview resources:
- [Debugger Skill Guide](mcp-steroid://skill/debugger-skill) - Essential debugger knowledge
