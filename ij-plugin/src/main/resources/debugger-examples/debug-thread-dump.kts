/**
 * Build Thread Dump
 *
 * Collect stack frames for each execution stack and print a basic thread dump.
 * Uses XExecutionStack.computeStackFrames to gather frames asynchronously.
 *
 * IntelliJ APIs: XDebuggerManager, XExecutionStack, XStackFrame
 */
import com.intellij.xdebugger.XDebuggerManager
import com.intellij.xdebugger.frame.XExecutionStack
import com.intellij.xdebugger.frame.XStackFrame
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

execute {
    waitForSmartMode()

    val session = XDebuggerManager.getInstance(project).currentSession
        ?: error("No debug session. Start one first.")

    if (!session.isSuspended) {
        error("Session is running. Pause it before building a thread dump.")
    }

    val suspendContext = session.suspendContext
        ?: error("No suspend context available.")

    val stacks = suspendContext.executionStacks
    println("Thread dump for", stacks.size, "stacks")

    stacks.forEach { stack ->
        println("Thread:", stack.displayName)
        val frames = collectFrames(stack)
        if (frames.isEmpty()) {
            println("  <no frames>")
            return@forEach
        }
        frames.forEachIndexed { index, frame ->
            val position = frame.sourcePosition
            val location = if (position != null) {
                "${position.file.name}:${position.line + 1}"
            } else {
                "<no position>"
            }
            println("  #$index $location")
        }
    }
}

suspend fun collectFrames(stack: XExecutionStack): List<XStackFrame> {
    return suspendCancellableCoroutine { cont ->
        val collected = mutableListOf<XStackFrame>()
        stack.computeStackFrames(0, object : XExecutionStack.XStackFrameContainer {
            override fun addStackFrames(stackFrames: List<out XStackFrame>, last: Boolean) {
                collected.addAll(stackFrames)
                if (last && cont.isActive) {
                    cont.resume(collected)
                }
            }

            override fun errorOccurred(errorMessage: String) {
                if (cont.isActive) {
                    cont.resume(collected)
                }
            }

            override fun isObsolete(): Boolean = false
        })
    }
}

/**
 * ## See Also
 *
 * Related debugger operations:
 * - [Debug Run Configuration](mcp-steroid://debugger/debug-run-configuration) - Start debugging
 * - [Debug Session Control](mcp-steroid://debugger/debug-session-control) - Pause/resume/stop
 * - [List Threads](mcp-steroid://debugger/debug-list-threads) - Inspect execution stacks
 * - [Set Line Breakpoint](mcp-steroid://debugger/set-line-breakpoint) - Create and manage breakpoints
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
