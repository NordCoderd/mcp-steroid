IDE: Demo Debug Test (End-to-end)

End-to-end demo that creates a debug run configuration for DemoTestByJonnyzzz, runs it in Debug mode, resumes if paused, waits for completion, and prints results.

IntelliJ API used:
- RunManager, JUnitConfiguration
- ProgramRunnerUtil, ExecutorRegistry, DefaultDebugExecutor
- XDebuggerManager
- RunContentManager, SMTRunnerConsoleView

Expected: DemoTestByJonnyzzz fails (intentional)
Cleanup:
- When you no longer need debugging, stop sessions on EDT:
  withContext(Dispatchers.EDT) { XDebuggerManager.getInstance(project).debugSessions.forEach { it.stop() } }
