Step Over

Step over the current line in a suspended debug session and wait for the debugger to re-suspend.

```kotlin
import com.intellij.xdebugger.XDebuggerManager
import com.intellij.xdebugger.XDebugSessionListener
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout
import kotlin.time.Duration.Companion.seconds

val session = XDebuggerManager.getInstance(project).currentSession
    ?: error("No debug session. Start one first.")
check(session.isSuspended) { "Session is not suspended." }

val beforePos = session.currentStackFrame?.sourcePosition
val beforeLine = (beforePos?.line ?: -1) + 1
println("Before step: ${beforePos?.file?.name}:$beforeLine")

// Set up listener BEFORE stepping — sessionPaused fires when the step completes
val stepDone = CompletableDeferred<Unit>()
val listener = object : XDebugSessionListener {
    override fun sessionPaused() { stepDone.complete(Unit) }
    override fun sessionStopped() { stepDone.completeExceptionally(Exception("Session stopped during step")) }
}
session.addSessionListener(listener)

try {
    // Step over must run on EDT
    withContext(Dispatchers.EDT) {
        session.stepOver(false)
    }

    // Wait for re-suspension (sessionPaused fires for both breakpoint hits and step completions)
    withTimeout(30.seconds) { stepDone.await() }

    val afterPos = session.currentStackFrame?.sourcePosition
    val afterLine = (afterPos?.line ?: -1) + 1
    println("After step: ${afterPos?.file?.name}:$afterLine")
} catch (e: kotlinx.coroutines.TimeoutCancellationException) {
    println("Debugger did not re-suspend after step over within 30 seconds")
} finally {
    session.removeSessionListener(listener)
}
```

## Scope changes after stepping

```kotlin
// IMPORTANT: After step-over, the debugger may land in a different scope:
//
// 1. If you step over a method call, the debugger first enters the called method,
//    executes it, and returns. The next suspension may be:
//    - The next line in the SAME method (normal case)
//    - The closing brace of the CALLED method (if there's a breakpoint inside it)
//    - A completely different method (if an exception handler is triggered)
//
// 2. Variables from the CALLING scope are NOT accessible when the debugger is
//    inside a different method's scope. For example, if test code calls
//    `line.AddSegment(0, 50, value)` and step-over lands inside AddSegment,
//    the variable `line` is not accessible — but `this.mySegments` is.
//
// 3. After any step, old XValue instances are invalidated. Always get fresh
//    frame, evaluator, and position from session.currentStackFrame.
//
// 4. Use `this.fieldName` to access instance state when the debugger is inside
//    a method and the caller's local variables are not in scope.
println("See code block above for the complete step-over pattern")
```

# See also

Related debugger operations:
- [Evaluate Expression](mcp-steroid://debugger/evaluate-expression) - Evaluate variables after stepping
- [Wait for Suspend](mcp-steroid://debugger/wait-for-suspend) - Wait for breakpoint hit
- [Debug Session Control](mcp-steroid://debugger/debug-session-control) - Resume, stop session

Overview resources:
- [Debugger Skill Guide](mcp-steroid://prompt/debugger-skill) - Essential debugger knowledge
