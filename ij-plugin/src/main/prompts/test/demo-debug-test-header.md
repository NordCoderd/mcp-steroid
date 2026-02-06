Test: Demo Debug Test (End-to-end)

This example creates/updates the demo JUnit configuration, starts it in Debug mode, resumes the debugger if it pauses, waits for completion, and prints test results.

IntelliJ API used:
- RunManager, JUnitConfiguration
- ProgramRunnerUtil, ExecutorRegistry, DefaultDebugExecutor
- XDebuggerManager
- RunContentManager, SMTRunnerConsoleView

Expected: DemoTestByJonnyzzz fails (intentional)
Cleanup:
- When you no longer need debugging, stop sessions on EDT:
  withContext(Dispatchers.EDT) { XDebuggerManager.getInstance(project).debugSessions.forEach { it.stop() } }
