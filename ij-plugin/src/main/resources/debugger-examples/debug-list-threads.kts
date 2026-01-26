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

/**
 * ## See Also
 *
 * Related debugger operations:
 * - [Debug Run Configuration](mcp-steroid://debugger/debug-run-configuration) - Start debugging
 * - [Debug Session Control](mcp-steroid://debugger/debug-session-control) - Pause/resume/stop
 * - [Set Line Breakpoint](mcp-steroid://debugger/set-line-breakpoint) - Create and manage breakpoints
 * - [Thread Dump](mcp-steroid://debugger/debug-thread-dump) - Generate thread dumps
 *
 * Related IDE operations:
 * - [Run Configuration](mcp-steroid://ide/run-configuration) - List and execute run configs
 *
 * Related test operations:
 * - [Run Tests](mcp-steroid://test/run-tests) - Execute test configuration
 * - [Wait for Completion](mcp-steroid://test/wait-for-completion) - Poll test status
 *
 * Overview resources:
 * - [Debugger Examples Overview](mcp-steroid://debugger/overview) - All debugger operations
 * - [Debugger Skill Guide](mcp-steroid://skill/debugger-guide) - Essential debugger knowledge
 */
