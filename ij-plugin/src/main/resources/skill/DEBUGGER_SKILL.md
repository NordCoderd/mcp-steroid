---
name: intellij-mcp-steroid-debugger
description: Use IntelliJ debugger APIs via steroid_execute_code to set breakpoints, start debug sessions, pause/resume, inspect threads, and build thread dumps.
---

# IntelliJ Debugger Skill

Use IntelliJ debugger APIs from `steroid_execute_code` to control debug sessions and inspect runtime state.

## Quickstart

1) Load `intellij://debugger/overview` and pick the examples you need.
2) Set breakpoints (example: `intellij://debugger/set-line-breakpoint`).
3) Start a debug run configuration (example: `intellij://debugger/debug-run-configuration`).
4) Pause/resume and inspect session state (example: `intellij://debugger/debug-session-control`).
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
- `XDebuggerUtil.getLineBreakpointTypes()` + `addLineBreakpoint(...)`

**Sessions**
- `XDebuggerManager.getInstance(project).currentSession`
- `XDebugSession.pause()`, `resume()`, `stop()`

**Threads / stacks**
- `XDebugSession.getSuspendContext()`
- `XSuspendContext.getExecutionStacks()`
- `XExecutionStack.getTopFrame()` or `computeStackFrames(...)`

## Common pitfalls

- `currentSession` is null until a debug run starts.
- Thread/stack info is only available while the session is suspended.
- `computeStackFrames` is async; use `suspendCancellableCoroutine` to await results.
- Start run configurations on EDT (use `withContext(Dispatchers.EDT)`).

## Debugger resources

- `intellij://debugger/overview`
- `intellij://debugger/set-line-breakpoint`
- `intellij://debugger/debug-run-configuration`
- `intellij://debugger/debug-session-control`
- `intellij://debugger/debug-list-threads`
- `intellij://debugger/debug-thread-dump`
