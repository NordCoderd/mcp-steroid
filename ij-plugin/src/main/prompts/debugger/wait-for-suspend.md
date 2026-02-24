Wait for Debugger Suspend

Poll until the debugger suspends at a breakpoint. Use this after starting a debug session

```kotlin
import com.intellij.xdebugger.XDebuggerManager

val debuggerManager = XDebuggerManager.getInstance(project)

// Poll up to 15 seconds for the debugger to suspend at a breakpoint
var suspended = false
repeat(30) { attempt ->
    val session = debuggerManager.currentSession
    if (session != null && session.isSuspended) {
        val frame = session.currentStackFrame
        val pos = frame?.sourcePosition
        val fileName = pos?.file?.name ?: "?"
        val line = (pos?.line ?: -1) + 1  // API is 0-indexed, display as 1-indexed
        println("Debugger suspended at: $fileName:$line (attempt ${attempt + 1})")
        suspended = true
        return
    }
    delay(500)
}

if (!suspended) {
    val session = debuggerManager.currentSession
    if (session == null) {
        println("No debug session found. Did the debug configuration start correctly?")
    } else {
        println("Debug session exists but not suspended after 15 seconds.")
        println("Session: ${session.sessionName}, stopped: ${session.isStopped}")
    }
}
```
