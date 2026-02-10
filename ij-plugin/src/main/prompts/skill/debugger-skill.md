---
name: intellij-mcp-steroid-debugger
description: Use IntelliJ debugger APIs via steroid_execute_code to set breakpoints, start debug sessions, evaluate variables, step through code, and inspect threads.
---

# IntelliJ Debugger Skill

Use IntelliJ debugger APIs from `steroid_execute_code` to control debug sessions, evaluate variables, and step through code.

## Quickstart

1) Load `mcp-steroid://debugger/overview` for the recommended workflow.
2) Set breakpoints: `mcp-steroid://debugger/set-line-breakpoint`
3) Create run config if needed: `mcp-steroid://debugger/create-application-config`
4) Start debug session: `mcp-steroid://debugger/debug-run-configuration`
5) Wait for breakpoint hit: `mcp-steroid://debugger/wait-for-suspend`
6) **Evaluate variables**: `mcp-steroid://debugger/evaluate-expression`
7) **Step over**: `mcp-steroid://debugger/step-over`
8) Evaluate again to see changes.

## Stateful exec_code workflow

`exec_code` is stateful. Split debugger work into multiple short calls:

- Call #1: set breakpoints
- Call #2: create run config (if needed) + start debug run
- Call #3: wait for suspend (poll `isSuspended`)
- Call #4: evaluate variables at breakpoint
- Call #5: step over
- Call #6: evaluate again to compare before/after
- Call #7: stop debugger

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
function from `mcp-steroid://debugger/evaluate-expression` with ALL imports.

Common import mistakes:
- `com.intellij.xdebugger.frame.XValuePresentation` (wrong package)
- `com.intellij.xdebugger.frame.presentation.XValuePresentation` (correct)
- `XDebugValue` or `XDebuggerEvaluationResult` (do NOT exist - the correct type is `XValue`)

The callback receives `XValue` (from `com.intellij.xdebugger.frame`), NOT `XDebugValue`.
The helper uses `XValuePresentationUtil.computeValueText()` to avoid complex callback implementations.

## Common pitfalls

- `currentSession` is null until a debug run starts.
- Thread/stack info is only available while the session is suspended.
- `computeStackFrames` is async; use `suspendCancellableCoroutine` to await results.
- `suspendCancellableCoroutine` import: `kotlinx.coroutines.suspendCancellableCoroutine`.
- Start run configurations on EDT (use `withContext(Dispatchers.EDT)`).
- UI calls like `FileEditorManager.openFile(...)` must run on EDT.
- `ProgramRunnerUtil` import: `com.intellij.execution.ProgramRunnerUtil` (not `com.intellij.execution.runners.ProgramRunnerUtil`).
- **Import errors**: Use exact imports from the MCP resource examples - `XValuePresentation` is in `presentation` subpackage!
- `XDebuggerEvaluator.evaluate(...)` is callback-based; do not assign its return value.
- The callback type is nested: `XDebuggerEvaluator.XEvaluationCallback` (not a top-level `XEvaluationCallback` import).
- The callback's `evaluated()` method receives `XValue`, NOT `XDebugValue` or `XDebuggerEvaluationResult`.
- For run config creation, use `RunManager.createConfiguration(name, factory)` + `RunManager.addConfiguration(settings)` (avoid deprecated add/store shortcuts).
- Keep breakpoint API usage on `XDebuggerUtil` public methods; avoid `impl`/`internal` helpers from `xdebugger-impl`.
- Wrap `FilenameIndex`, PSI, and document access in `readAction {}`.
- In `steroid_execute_code`, do not use `return@executeSteroidCode`; scripts are already the suspend body.
- Do not fetch `mcp-steroid://...` URIs via HTTP/web tools; load them via MCP resources.
- For final bug reports, print and reuse the exact source line from `Document` text; do not infer or rewrite it from memory.

## Related Resources

### Skill Guides
- [IntelliJ API Power User Guide](mcp-steroid://skill/skill) - Essential IntelliJ API patterns
- [Debugger Skill Guide](mcp-steroid://skill/debugger-skill) - This guide
- [Test Runner Skill Guide](mcp-steroid://skill/test-skill) - Test execution workflows

### Debugger Resources
- [Debugger Overview](mcp-steroid://debugger/overview) - Complete debugger examples overview
- [Set Line Breakpoint](mcp-steroid://debugger/set-line-breakpoint) - Create and manage breakpoints
- [Create Application Config](mcp-steroid://debugger/create-application-config) - Create run configuration
- [Debug Run Configuration](mcp-steroid://debugger/debug-run-configuration) - Start debugging a run config
- [Wait for Suspend](mcp-steroid://debugger/wait-for-suspend) - Wait for breakpoint hit
- [Evaluate Expression](mcp-steroid://debugger/evaluate-expression) - Evaluate variables at breakpoint
- [Step Over](mcp-steroid://debugger/step-over) - Step through code
- [Debug Session Control](mcp-steroid://debugger/debug-session-control) - Pause, resume, and stop sessions
- [List Threads](mcp-steroid://debugger/debug-list-threads) - Inspect execution stacks and threads
- [Thread Dump](mcp-steroid://debugger/debug-thread-dump) - Generate complete thread dumps

### Related Example Guides
- [Test Examples](mcp-steroid://test/overview) - Test execution and result inspection
- [IDE Examples](mcp-steroid://ide/overview) - IDE power operations
- [Open Project Examples](mcp-steroid://open-project/overview) - Project opening workflows
