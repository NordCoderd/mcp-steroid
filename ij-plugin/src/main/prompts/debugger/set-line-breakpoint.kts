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

val breakpoint = breakpointManager.allBreakpoints
    .filterIsInstance<XLineBreakpoint<*>>()
    .firstOrNull { it.fileUrl == virtualFile.url && it.line == lineIndex }
    ?: error("Breakpoint was not created for line $lineNumberInEditor")

println("Created breakpoint:", breakpoint)
