---
name: mcp-steroid-debugger
description: Use IntelliJ debugger APIs via steroid_execute_code to set breakpoints, start debug sessions, evaluate variables, step through code, and inspect threads.
---

# IntelliJ Debugger Skill

Use IntelliJ debugger APIs from `steroid_execute_code` to control debug sessions, evaluate variables, and step through code.

## Quickstart

1) Read `mcp-steroid://debugger/overview` for the complete workflow and resource list.
2) **Read each individual resource** before using it -- each one contains complete, copy-paste-ready code.
3) Set breakpoints: read `mcp-steroid://debugger/set-line-breakpoint`, adapt the file path and line number
4) Create run config if needed: read `mcp-steroid://debugger/create-application-config`, set the main class
5) Start debug session: read `mcp-steroid://debugger/debug-run-configuration`
6) Wait for breakpoint hit: read `mcp-steroid://debugger/wait-for-suspend`
7) **Evaluate variables**: read `mcp-steroid://debugger/evaluate-expression` -- copy the helper exactly
8) **Step over**: read `mcp-steroid://debugger/step-over`
9) Evaluate again to see how values change after each line executes.

**IMPORTANT**: Read the actual MCP resource content for each step. The resources contain working
IntelliJ API code with correct imports that you can directly adapt and pass to `steroid_execute_code`.
Do NOT invent your own API calls -- the IntelliJ debugger API has tricky callback patterns.

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
