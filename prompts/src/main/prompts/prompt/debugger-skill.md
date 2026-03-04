IntelliJ Debugger Skill Guide

Use IntelliJ debugger APIs via steroid_execute_code to set breakpoints, start debug sessions, pause/resume, inspect threads, and build thread dumps.


# IntelliJ Debugger Skill

Use IntelliJ debugger APIs from `steroid_execute_code` to control debug sessions, evaluate variables, and step through code.

## Quickstart

1) Set breakpoints: read `mcp-steroid://debugger/add-breakpoint`, adapt the file path and line number
2) Launch the debug session:
   - For **JUnit/Kotlin test**: read `mcp-steroid://debugger/demo-debug-test` — it creates a JUnitConfiguration (with module) and launches it
   - For **Rider/.NET test**: read `mcp-steroid://debugger/demo-debug-test` — it opens the file and fires `RiderUnitTestDebugContextAction`
   - For **existing run config** (Application, Gradle): read `mcp-steroid://debugger/debug-run-configuration`
   - For **new Application config**: read `mcp-steroid://debugger/create-application-config` first
3) Wait for breakpoint hit: read `mcp-steroid://debugger/wait-for-suspend`
4) **Evaluate variables**: read `mcp-steroid://debugger/evaluate-expression` (includes the reusable `eval()` helper inline)
   - The `eval()` helper automatically retries on `"Collecting data..."` — just use it as-is.
5) **Step over** (only if you need to observe state changes across multiple lines):
   read `mcp-steroid://debugger/step-over`. **Skip this step if the bug is already visible from evaluation at the breakpoint.**
6) Stop the debug session when done.

**IMPORTANT**: Read the actual MCP resource content for each step. The resources contain working
IntelliJ API code with correct imports that you can directly adapt and pass to `steroid_execute_code`.
Do NOT invent your own API calls -- the IntelliJ debugger API has tricky callback patterns.

## Stateful steroid_execute_code workflow

`steroid_execute_code` is stateful. Split debugger work into multiple short calls:

- Call #1: set breakpoints
- Call #2: create run config (if needed) + start debug run
- Call #3: wait for suspend (event-driven via `XDebugSessionListener`)
- Call #4: evaluate variables at breakpoint — if the bug is visible here, skip to #6
- Call #5: step over (optional — only if you need to see state changes across lines)
- Call #6: stop debugger (and evaluate again if you stepped)

Use event-driven `XDebugSessionListener` + `CompletableDeferred` instead of polling loops.

## Efficient Code Discovery

To find implementation files quickly:
1. Read the test file first — import paths and class names tell you where the implementation is
2. Use one `Grep` with the function/class name to locate the source — avoid chaining multiple `Glob` calls
3. Set the breakpoint at the exact suspicious line (filter, sort, map call) — no need to step through from the top

## Key APIs to use

**Breakpoints** (idempotent — safe to call repeatedly)
- `XDebuggerManager.getInstance(project).breakpointManager`
- Use `findBreakpointsAtLine(type, file, line)` to check if breakpoint exists, then `addLineBreakpoint(type, url, line, props)` if absent
- Cast breakpoint type to `XLineBreakpointType<XBreakpointProperties<*>>` — works across ALL IDEs (Java, Kotlin, C#/Rider)
- Do NOT cast to `Nothing?` or `Void` — Rider uses `DotNetLineBreakpointProperties`, not Void
- Do NOT use `toggleLineBreakpoint` — it REMOVES existing breakpoints (toggle semantics)

**Sessions**
- `XDebuggerManager.getInstance(project).currentSession`
- `XDebugSession.pause()`, `resume()`, `stop()`

**Threads / stacks**
- `XDebugSession.getSuspendContext()`
- `XSuspendContext.getExecutionStacks()`
- `XExecutionStack.getTopFrame()` or `computeStackFrames(...)`

## Expression Evaluation

**CRITICAL**: Do NOT write your own evaluation helper! Copy the complete `eval()`
function from `mcp-steroid://debugger/evaluate-expression` with ALL imports.

Common mistakes:
- `com.intellij.xdebugger.frame.XValuePresentation` (wrong package) → use `com.intellij.xdebugger.frame.presentation.XValuePresentation`
- `XDebugValue` or `XDebuggerEvaluationResult` (do NOT exist) → the correct type is `XValue`
- Expression body `= deferred.complete(value)` → causes type mismatch (`Boolean` vs `Unit`). Use block body `{ deferred.complete(value) }`
- **Awaiting `value.isReady` BEFORE `computePresentation` in Rider** → 30-second deadlock that crashes MCP server. In Rider/DotNetValue, `isReady` only completes INSIDE `computePresentation`'s async coroutine. The `eval()` helper is already correct — do NOT add an `isReady.await()` call.

The callback receives `XValue` (from `com.intellij.xdebugger.frame`), NOT `XDebugValue`.
The helper uses `XValuePresentationUtil.computeValueText()` to avoid complex callback implementations.

## Scope After Stepping

After `stepOver()`, the debugger may land in a different method scope:
- Local variables from the caller are NOT accessible inside the called method
- Use `this.fieldName` to access instance state at method boundaries
- Old `XValue` instances are invalidated — always get fresh `evaluator` from `session.currentStackFrame`

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
- Keep breakpoint API usage on `XBreakpointManager` public methods; avoid `impl`/`internal` helpers from `xdebugger-impl`.
- `toggleLineBreakpoint` is a TOGGLE — if a breakpoint already exists, it REMOVES it. Use `findBreakpointsAtLine` + `addLineBreakpoint` instead.
- Wrap `FilenameIndex`, PSI, and document access in `readAction {}`.
- In `steroid_execute_code`, do not use `return@executeSteroidCode`; scripts are already the suspend body.
- Do not fetch `mcp-steroid://...` URIs via HTTP/web tools; load them via MCP resources.
- For final bug reports, print and reuse the exact source line from `Document` text; do not infer or rewrite it from memory.

## Related Resources

### Skill Guides
- [IntelliJ API Power User Guide](mcp-steroid://prompt/skill) - Essential IntelliJ API patterns
- [Debugger Skill Guide](mcp-steroid://prompt/debugger-skill) - This guide
- [Test Runner Skill Guide](mcp-steroid://prompt/test-skill) - Test execution workflows

### Debugger Resources
- [Debugger Overview](mcp-steroid://debugger/overview) - Complete debugger examples overview
- [Add Breakpoint](mcp-steroid://debugger/add-breakpoint) - Add breakpoint idempotently
- [Remove Breakpoint](mcp-steroid://debugger/remove-breakpoint) - Remove breakpoints from a line
- [Set Line Breakpoint](mcp-steroid://debugger/set-line-breakpoint) - Combined add/remove reference
- [Create Application Config](mcp-steroid://debugger/create-application-config) - Create run configuration
- [Debug Run Configuration](mcp-steroid://debugger/debug-run-configuration) - Start debugging a run config
- [Wait for Suspend](mcp-steroid://debugger/wait-for-suspend) - Wait for breakpoint hit (event-driven)
- [Evaluate Expression](mcp-steroid://debugger/evaluate-expression) - Full evaluation example with reusable eval() helper
- [Step Over](mcp-steroid://debugger/step-over) - Step through code (with scope change docs)
- [Debug Session Control](mcp-steroid://debugger/debug-session-control) - Pause, resume, and stop sessions
- [List Threads](mcp-steroid://debugger/debug-list-threads) - Inspect execution stacks and threads
- [Thread Dump](mcp-steroid://debugger/debug-thread-dump) - Generate complete thread dumps

### Related Example Guides
- [Test Examples](mcp-steroid://test/overview) - Test execution and result inspection
- [IDE Examples](mcp-steroid://ide/overview) - IDE power operations
- [Open Project Examples](mcp-steroid://open-project/overview) - Project opening workflows
