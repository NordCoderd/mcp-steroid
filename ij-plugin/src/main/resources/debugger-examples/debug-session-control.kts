/**
 * Control Debug Session
 *
 * Pause/resume/stop the current debug session.
 * Use this after you start a debug run configuration.
 *
 * IntelliJ APIs: XDebuggerManager, XDebugSession
 */
import com.intellij.openapi.application.EDT
import com.intellij.xdebugger.XDebuggerManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext


val session = XDebuggerManager.getInstance(project).currentSession
    ?: error("No debug session. Start one first.")

println("Session:", session.sessionName, "suspended:", session.isSuspended)

withContext(Dispatchers.EDT) {
    session.pause()
}
println("Pause requested. Suspended:", session.isSuspended)

withContext(Dispatchers.EDT) {
    session.resume()
}
println("Resume requested. Suspended:", session.isSuspended)

// session.stop() will terminate the debugged process.

/**
 * ## See Also
 *
 * Related debugger operations:
 * - [Debug Run Configuration](mcp-steroid://debugger/debug-run-configuration) - Start debugging
 * - [Set Line Breakpoint](mcp-steroid://debugger/set-line-breakpoint) - Create and manage breakpoints
 * - [List Threads](mcp-steroid://debugger/debug-list-threads) - Inspect execution stacks
 * - [Thread Dump](mcp-steroid://debugger/debug-thread-dump) - Generate thread dumps
 *
 * Related IDE operations:
 * - [Run Configuration](mcp-steroid://ide/run-configuration) - List and execute run configs
 *
 * Related test operations:
 * - [Run Tests](mcp-steroid://test/run-tests) - Execute test configuration
 *
 * Overview resources:
 * - [Debugger Examples Overview](mcp-steroid://debugger/overview) - All debugger operations
 * - [Debugger Skill Guide](mcp-steroid://skill/debugger-guide) - Essential debugger knowledge
 */
