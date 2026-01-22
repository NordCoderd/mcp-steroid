/**
 * List Debug Threads
 *
 * Enumerate execution stacks (threads) for a suspended debug session.
 * Prints the thread names and top frame locations if available.
 *
 * IntelliJ APIs: XDebuggerManager, XSuspendContext, XExecutionStack
 */
import com.intellij.xdebugger.XDebuggerManager

execute {
    waitForSmartMode()

    val session = XDebuggerManager.getInstance(project).currentSession
        ?: error("No debug session. Start one first.")

    if (!session.isSuspended) {
        error("Session is running. Pause it before listing threads.")
    }

    val suspendContext = session.suspendContext
        ?: error("No suspend context available.")

    val stacks = suspendContext.executionStacks
    println("Execution stacks:", stacks.size)

    stacks.forEach { stack ->
        val topFrame = stack.topFrame
        val position = topFrame?.sourcePosition
        val location = if (position != null) {
            "${position.file.name}:${position.line + 1}"
        } else {
            "<no position>"
        }
        println("Thread:", stack.displayName, "top:", location)
    }
}
