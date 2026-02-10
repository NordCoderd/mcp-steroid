import com.intellij.xdebugger.XDebuggerManager

val session = XDebuggerManager.getInstance(project).currentSession
    ?: error("No debug session. Start one first.")
check(session.isSuspended) { "Session is not suspended." }

val beforePos = session.currentStackFrame?.sourcePosition
val beforeLine = (beforePos?.line ?: -1) + 1
println("Before step: ${beforePos?.file?.name}:$beforeLine")

// Step over must run on EDT
withContext(Dispatchers.EDT) {
    session.stepOver(false)
}

// Wait for the debugger to re-suspend after stepping
repeat(20) {
    if (session.isSuspended) {
        val afterPos = session.currentStackFrame?.sourcePosition
        val afterLine = (afterPos?.line ?: -1) + 1
        println("After step: ${afterPos?.file?.name}:$afterLine")
        return
    }
    delay(250)
}

println("Debugger did not re-suspend after step over within 5 seconds")
