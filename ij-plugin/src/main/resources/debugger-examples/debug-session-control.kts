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

execute {
    waitForSmartMode()

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
}
