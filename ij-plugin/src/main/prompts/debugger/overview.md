# Debugger Examples

These resources show how to drive IntelliJ's debugger APIs from `steroid_execute_code`.
Use them as building blocks to set breakpoints, start a debug session, evaluate variables,
step through code, and inspect threads/stack frames.

## Getting Started

If you need to debug a program, follow this approach:

1. **Read this overview** to understand the overall workflow
2. **Read each resource below** before executing its step -- each contains a complete, copy-paste-ready script
3. **Execute each step as a separate `steroid_execute_code` call** -- do NOT combine steps into one large script
4. **Adapt the placeholder values** (file paths, line numbers, class names) in each script to your specific task

The resources are self-contained: each one includes all necessary imports, API calls, and
error handling. You do not need to know IntelliJ APIs in advance -- just read the resource,
adapt the parameters, and pass the code to `steroid_execute_code`.

## Recommended Workflow

Follow this sequence of resources for a complete debug session:

1. `mcp-steroid://debugger/set-line-breakpoint` - set breakpoint on target line
2. `mcp-steroid://debugger/create-application-config` - create run config (if needed)
3. `mcp-steroid://debugger/debug-run-configuration` - start debug session
4. `mcp-steroid://debugger/wait-for-suspend` - wait for breakpoint hit
5. `mcp-steroid://debugger/evaluate-expression` - evaluate variables at breakpoint
6. `mcp-steroid://debugger/step-over` - step to next line
7. `mcp-steroid://debugger/evaluate-expression` - evaluate again to see changes

Each step should be a separate `steroid_execute_code` call. Do NOT combine steps into one large script.

## Available resources

### Setup
- `mcp-steroid://debugger/set-line-breakpoint` - create a line breakpoint
- `mcp-steroid://debugger/create-application-config` - create Application run configuration
- `mcp-steroid://debugger/debug-run-configuration` - start a run configuration in Debug

### Inspection (the essential ones for finding bugs)
- `mcp-steroid://debugger/wait-for-suspend` - wait for debugger to suspend at breakpoint
- `mcp-steroid://debugger/evaluate-expression` - evaluate variables/expressions at breakpoint
- `mcp-steroid://debugger/step-over` - step over current line and observe changes

### Session management
- `mcp-steroid://debugger/debug-session-control` - pause/resume/stop the current session
- `mcp-steroid://debugger/debug-list-threads` - list execution stacks (threads)
- `mcp-steroid://debugger/debug-thread-dump` - build a basic thread dump from stacks

### Demos
- `mcp-steroid://debugger/demo-debug-test` - end-to-end debug + test results demo

## Tips

- If `steroid_execute_code` returns `Project not found`, call `steroid_list_projects` and reuse the exact `project_name`.
- Do not hardcode line numbers; locate the target statement by text (for example, the `sortedByDescending` call) before placing breakpoints.
- Use `mcp-steroid://debugger/set-line-breakpoint` for breakpoint setup (preferred API: `toggleLineBreakpoint` on EDT).
- Use `mcp-steroid://debugger/debug-run-configuration` for debug launch (uses `com.intellij.execution.ProgramRunnerUtil`).
- **For variable evaluation, always copy the `evaluateExpression()` helper from `mcp-steroid://debugger/evaluate-expression`**. Do NOT write your own evaluation code -- the callback API is tricky and easy to get wrong.
- UI calls like `FileEditorManager.openFile(...)` must run on EDT (`withContext(Dispatchers.EDT)`).
- Stop debug sessions when done: use debug-session-control or
  withContext(Dispatchers.EDT) { XDebuggerManager.getInstance(project).debugSessions.forEach { it.stop() } }
- API calls use 0-indexed lines; editor line 7 means API line 6.
- `debug-list-threads` and `debug-thread-dump` require a suspended session.
- Use `DefaultDebugExecutor` and `com.intellij.execution.ProgramRunnerUtil` to start debug configs.
- In `steroid_execute_code`, do not use `return@executeSteroidCode` or `return@executeSuspend`; use plain `return`.

## API Contracts (2025.3+)

- Run/debug launch: use `ProgramRunnerUtil.executeConfiguration(settings, executor)` with a real `Executor` (`DefaultDebugExecutor.getDebugExecutorInstance()` or `ExecutorRegistry.getInstance().getExecutorById(...)`).
- For `ApplicationConfiguration`, set entry point via `mainClassName = "..."` (avoid `setMainClassName(...)`).
- Run config creation: prefer `RunManager.createConfiguration(name, factory)` then `RunManager.addConfiguration(settings)`; choose storage via `settings.storeInDotIdeaFolder()` or `settings.storeInLocalWorkspace()` before add.
- Breakpoints: use `XDebuggerUtil.getInstance().toggleLineBreakpoint(project, file, line)` instead of internal/impl helpers.
- Session control: use `XDebuggerManager.getInstance(project).currentSession`, then `pause()`, `resume()`, `stepOver(...)`, `stop()`.
- Expression evaluation is callback-based. **Do NOT write your own callback implementation -- copy the complete `evaluateExpression()` helper from `mcp-steroid://debugger/evaluate-expression`.**
  Key types: `XDebuggerEvaluator.XEvaluationCallback` (nested, not top-level), callback receives `XValue` (from `com.intellij.xdebugger.frame`), presentation via `XValuePresentationUtil.computeValueText()`.
- Step over: `session.stepOver(false)` on EDT, then wait for re-suspension.
- PSI/VFS lookups (for example `FilenameIndex`, `PsiManager`, documents) must run in `readAction { ... }`.

## Failure-Recovery Pattern

When a debugger script fails with unresolved imports/APIs or runtime setup errors:

1. Stop and split work into short stateful calls (breakpoint setup -> debug launch -> inspect).
2. Reuse existing debugger resources instead of inventing large custom scripts.
3. If debug setup still fails, do one final source-level diagnosis call, print the exact buggy line text from the `Document`, and report root cause clearly.
4. Always include execute_code evidence (`Execution ID` / `execution_id`) in the final answer.

---

## Related Resources

### Skill Guides
- [IntelliJ API Power User Guide](mcp-steroid://skill/skill) - Core API reference and patterns
- [Debugger Skill Guide](mcp-steroid://skill/debugger-skill) - Essential debugger knowledge and workflows
- [Test Runner Skill Guide](mcp-steroid://skill/test-skill) - Test execution

### Debugger Examples
- [Debugger Overview](mcp-steroid://debugger/overview) - This document
- [Set Line Breakpoint](mcp-steroid://debugger/set-line-breakpoint) - Create and manage breakpoints
- [Create Application Config](mcp-steroid://debugger/create-application-config) - Create run configuration
- [Debug Run Configuration](mcp-steroid://debugger/debug-run-configuration) - Start debugging
- [Wait for Suspend](mcp-steroid://debugger/wait-for-suspend) - Wait for breakpoint hit
- [Evaluate Expression](mcp-steroid://debugger/evaluate-expression) - Evaluate variables at breakpoint
- [Step Over](mcp-steroid://debugger/step-over) - Step through code
- [Debug Session Control](mcp-steroid://debugger/debug-session-control) - Pause, resume, stop
- [Demo Debug Test](mcp-steroid://debugger/demo-debug-test) - End-to-end debug + test results demo
- [List Threads](mcp-steroid://debugger/debug-list-threads) - Inspect execution stacks
- [Thread Dump](mcp-steroid://debugger/debug-thread-dump) - Generate thread dumps

### Related Example Guides
- [Test Examples](mcp-steroid://test/overview) - Test execution and result inspection
- [IDE Examples](mcp-steroid://ide/overview) - IDE power operations
- [LSP Examples](mcp-steroid://lsp/overview) - Code navigation and intelligence
- [Open Project Examples](mcp-steroid://open-project/overview) - Project opening workflows
