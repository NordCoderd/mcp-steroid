Debugger: Demo Debug Test (End-to-end)

End-to-end demo that configures a JUnit test, runs it in Debug, resumes if paused, waits for completion, and prints test results.

IntelliJ API used:
- RunManager, JUnitConfiguration
- ProgramRunnerUtil, ExecutorRegistry, DefaultDebugExecutor
- XDebuggerManager
- RunContentManager, SMTRunnerConsoleView

Expected: DemoTestByJonnyzzz fails (intentional)
Cleanup:
- When you no longer need debugging, stop sessions on EDT:
  withContext(Dispatchers.EDT) { XDebuggerManager.getInstance(project).debugSessions.forEach { it.stop() } }
