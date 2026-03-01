Control Debug Session

Pause/resume/stop the current debug session.

```kotlin
import com.intellij.xdebugger.XDebuggerManager

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
```

# See also

Related IDE operations:
- [Run Configuration](mcp-steroid://ide/run-configuration) - List and execute run configs

Related test operations:
- [Run Tests](mcp-steroid://test/run-tests) - Execute test configuration

Overview resources:
- [Debugger Skill Guide](mcp-steroid://skill/debugger-skill) - Essential debugger knowledge
