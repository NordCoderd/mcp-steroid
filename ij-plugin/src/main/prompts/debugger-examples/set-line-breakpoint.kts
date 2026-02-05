//
//
//
//
//
//
//
//
//
//
//
//
//
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.xdebugger.XDebuggerManager
import com.intellij.xdebugger.XDebuggerUtil
import java.nio.file.Paths


val filePath = "src/main/kotlin/com/jonnyzzz/intellij/mcp/execution/ExecutionManager.kt"
val lineNumber = 41

val projectRoot = project.basePath ?: error("Project basePath is null")
val absolutePath = Paths.get(projectRoot, filePath).toString()
val virtualFile = LocalFileSystem.getInstance().findFileByPath(absolutePath)
    ?: error("File not found: $absolutePath")

val breakpointManager = XDebuggerManager.getInstance(project).breakpointManager
val lineType = XDebuggerUtil.getInstance().getLineBreakpointTypes()
    .firstOrNull() ?: error("No line breakpoint types registered")

val lineIndex = lineNumber - 1
val breakpoint = writeAction {
    breakpointManager.addLineBreakpoint(lineType, virtualFile.url, lineIndex, null)
}

if (breakpoint == null) {
    error("Breakpoint was not created (type=${lineType.id}, line=$lineNumber)")
}

println("Created breakpoint:", breakpoint)
