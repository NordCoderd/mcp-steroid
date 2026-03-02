Add Line Breakpoint (Idempotent)

Add a line breakpoint at a file/line using the idempotent find-then-add pattern.

```kotlin
import com.intellij.xdebugger.XDebuggerManager
import com.intellij.xdebugger.XDebuggerUtil
import com.intellij.xdebugger.breakpoints.XBreakpointProperties
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

// Cast to star-projection — works for ALL IDEs (Java, Kotlin, C#/Rider, etc.)
// Do NOT cast to Nothing? — Rider uses DotNetLineBreakpointProperties, not Void
@Suppress("UNCHECKED_CAST")
val bpType = breakpointType as XLineBreakpointType<XBreakpointProperties<*>>

// Check if breakpoint already exists (idempotent — safe to call repeatedly)
val existing = breakpointManager.findBreakpointsAtLine(bpType, virtualFile, lineIndex)
if (existing.isNotEmpty()) {
    println("Breakpoint already exists at $filePath:$lineNumberInEditor")
    println("Breakpoint:", existing.first())
} else {
    val properties = readAction { bpType.createBreakpointProperties(virtualFile, lineIndex) }
    val breakpoint = breakpointManager.addLineBreakpoint(bpType, virtualFile.url, lineIndex, properties)
    println("Created breakpoint at $filePath:$lineNumberInEditor")
    println("Breakpoint:", breakpoint)
}
```

```kotlin
// WARNING: Do NOT use toggleLineBreakpoint for "ensure breakpoint exists".
// toggleLineBreakpoint is a TOGGLE — it REMOVES an existing breakpoint if present.
// The find-then-add pattern above is idempotent and always safe.
//
// - Line numbers are 0-indexed in the API (editor line 7 = API line 6)
// - addLineBreakpoint does NOT deduplicate — always check with findBreakpointsAtLine first
// - In Rider, breakpoints are registered asynchronously via the RD protocol
println("See code block above for the complete idempotent add-breakpoint pattern")
```

# See also

Related debugger operations:
- [Remove Breakpoint](mcp-steroid://debugger/remove-breakpoint) - Remove breakpoints from a line
- [Set Line Breakpoint](mcp-steroid://debugger/set-line-breakpoint) - Combined add/remove reference
- [Debug Run Configuration](mcp-steroid://debugger/debug-run-configuration) - Start debug session

Overview resources:
- [Debugger Skill Guide](mcp-steroid://prompt/debugger-skill) - Essential debugger knowledge
