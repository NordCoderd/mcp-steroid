import com.intellij.xdebugger.XDebuggerManager
import com.intellij.xdebugger.frame.XExecutionStack
import com.intellij.xdebugger.frame.XStackFrame
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume


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
