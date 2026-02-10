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
