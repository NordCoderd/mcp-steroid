---
name: intellij-mcp-steroid-debugger
description: Use IntelliJ debugger APIs via steroid_execute_code to set breakpoints, start debug sessions, pause/resume, inspect threads, and build thread dumps.
---

# IntelliJ Debugger Skill

Use IntelliJ debugger APIs from `steroid_execute_code` to control debug sessions and inspect runtime state.

## Quickstart

1) Load `mcp-steroid://debugger/overview` and pick the examples you need.
2) Set breakpoints (example: `mcp-steroid://debugger/set-line-breakpoint`).
3) Start a debug run configuration (example: `mcp-steroid://debugger/debug-run-configuration`).
4) Pause/resume and inspect session state (example: `mcp-steroid://debugger/debug-session-control`).
5) List threads or build a thread dump (examples: `debug-list-threads`, `debug-thread-dump`).

## Stateful exec_code workflow

`exec_code` is stateful. Split debugger work into multiple short calls:

- Call #1: set breakpoints
- Call #2: start debug run
- Call #3+: poll `XDebuggerManager.currentSession` and check `isSuspended`
- Call #4: list threads / top frames
- Call #5: thread dump (optional)

Avoid long waits or sleeps inside a single call; prefer multiple short polls.

## Key APIs to use

**Breakpoints**
- `XDebuggerManager.getInstance(project).breakpointManager`
- `XDebuggerUtil.getInstance().toggleLineBreakpoint(project, file, lineIndex)` (on EDT)

**Sessions**
- `XDebuggerManager.getInstance(project).currentSession`
- `XDebugSession.pause()`, `resume()`, `stop()`

**Threads / stacks**
- `XDebugSession.getSuspendContext()`
- `XSuspendContext.getExecutionStacks()`
- `XExecutionStack.getTopFrame()` or `computeStackFrames(...)`

## Expression Evaluation

**CRITICAL**: Do NOT write your own evaluation helper! Copy the complete `evaluateExpression()`
function from `docs/DEBUG_SCRIPT.md` with ALL imports. Common import mistakes:

- ❌ `com.intellij.xdebugger.frame.XValuePresentation` (wrong package)
- ✅ `com.intellij.xdebugger.frame.presentation.XValuePresentation` (correct)

The helper uses `XValuePresentationUtil.computeValueText()` to avoid complex callback implementations.

## Common pitfalls

- `currentSession` is null until a debug run starts.
- Thread/stack info is only available while the session is suspended.
- `computeStackFrames` is async; use `suspendCancellableCoroutine` to await results.
- `suspendCancellableCoroutine` import: `kotlinx.coroutines.suspendCancellableCoroutine`.
- Start run configurations on EDT (use `withContext(Dispatchers.EDT)`).
- `ProgramRunnerUtil` import: `com.intellij.execution.ProgramRunnerUtil` (not `com.intellij.execution.runners.ProgramRunnerUtil`).
- **Import errors**: Use exact imports from docs - `XValuePresentation` is in `presentation` subpackage!

## Related Resources

### Skill Guides
- [IntelliJ API Power User Guide](mcp-steroid://skill/skill) - Essential IntelliJ API patterns
- [Debugger Skill Guide](mcp-steroid://skill/debugger-skill) - This guide
- [Test Runner Skill Guide](mcp-steroid://skill/test-skill) - Test execution workflows

### Debugger Resources
- [Debugger Overview](mcp-steroid://debugger/overview) - Complete debugger examples overview
- [Set Line Breakpoint](mcp-steroid://debugger/set-line-breakpoint) - Create and manage breakpoints
- [Debug Run Configuration](mcp-steroid://debugger/debug-run-configuration) - Start debugging a run config
- [Debug Session Control](mcp-steroid://debugger/debug-session-control) - Pause, resume, and stop sessions
- [List Threads](mcp-steroid://debugger/debug-list-threads) - Inspect execution stacks and threads
- [Thread Dump](mcp-steroid://debugger/debug-thread-dump) - Generate complete thread dumps

### Related Example Guides
- [Test Examples](mcp-steroid://test/overview) - Test execution and result inspection
- [IDE Examples](mcp-steroid://ide/overview) - IDE power operations
- [Open Project Examples](mcp-steroid://open-project/overview) - Project opening workflows
