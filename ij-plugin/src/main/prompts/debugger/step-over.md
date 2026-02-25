Step Over

Step over the current line in a suspended debug session and wait for the debugger

```kotlin
import com.intellij.xdebugger.XDebuggerManager

val session = XDebuggerManager.getInstance(project).currentSession
    ?: error("No debug session. Start one first.")
check(session.isSuspended) { "Session is not suspended." }

val beforePos = session.currentStackFrame?.sourcePosition
val beforeLine = (beforePos?.line ?: -1) + 1
println("Before step: ${beforePos?.file?.name}:$beforeLine")

// Step over must run on EDT.
// After stepOver(), session.isSuspended becomes false (doResume clears it).
// We then wait for re-suspension at a new position.
// IMPORTANT: After step-over, the suspend context changes, which invalidates
// old XValue instances. If you evaluate expressions after step-over, you'll
// get fresh JavaValue instances that need async rendering for complex objects.
withContext(Dispatchers.EDT) {
    session.stepOver(false)
}

// Wait for session to become not-suspended first (step is in progress)
repeat(10) {
    if (!session.isSuspended) return@repeat
    delay(100)
}

// Wait for the debugger to re-suspend after stepping
repeat(50) {
    if (session.isSuspended) {
        val afterPos = session.currentStackFrame?.sourcePosition
        val afterLine = (afterPos?.line ?: -1) + 1
        println("After step: ${afterPos?.file?.name}:$afterLine")
        return
    }
    delay(200)
}

println("Debugger did not re-suspend after step over within 10 seconds")
```

# See also

Related debugger operations:
- [Evaluate Expression](mcp-steroid://debugger/evaluate-expression) - Evaluate variables after stepping
- [Wait for Suspend](mcp-steroid://debugger/wait-for-suspend) - Wait for breakpoint hit
- [Debug Session Control](mcp-steroid://debugger/debug-session-control) - Resume, stop session

Overview resources:
- [Debugger Skill Guide](mcp-steroid://skill/debugger-skill) - Essential debugger knowledge
