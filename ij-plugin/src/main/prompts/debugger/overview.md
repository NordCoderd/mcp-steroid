# Debugger Examples

These resources show how to drive IntelliJ's debugger APIs from `steroid_execute_code`.
Use them as building blocks to set breakpoints, start a debug session, pause/resume,
and inspect threads/stack frames.

Stateful exec_code note: the execute_code tool is stateful, so run these in multiple
short calls (set breakpoints -> start debug -> pause -> inspect) rather than one
long script with waits.

## Available resources

- `mcp-steroid://debugger/set-line-breakpoint` - create a line breakpoint
- `mcp-steroid://debugger/debug-run-configuration` - start a run configuration in Debug
- `mcp-steroid://debugger/demo-debug-test` - end-to-end debug + test results demo
- `mcp-steroid://debugger/debug-session-control` - pause/resume/stop the current session
- `mcp-steroid://debugger/debug-list-threads` - list execution stacks (threads)
- `mcp-steroid://debugger/debug-thread-dump` - build a basic thread dump from stacks

## Tips

- If `steroid_execute_code` returns `Project not found`, call `steroid_list_projects` and reuse the exact `project_name`.
- Do not hardcode line numbers; locate the target statement by text (for example, the `sortedByDescending` call) before placing breakpoints.
- Use `mcp-steroid://debugger/set-line-breakpoint` for breakpoint setup (preferred API: `toggleLineBreakpoint` on EDT).
- Use `mcp-steroid://debugger/debug-run-configuration` for debug launch (uses `com.intellij.execution.ProgramRunnerUtil`).
- Stop debug sessions when done: use debug-session-control or
  withContext(Dispatchers.EDT) { XDebuggerManager.getInstance(project).debugSessions.forEach { it.stop() } }
- API calls use 0-indexed lines; editor line 7 means API line 6.
- `debug-list-threads` and `debug-thread-dump` require a suspended session.
- Use `DefaultDebugExecutor` and `com.intellij.execution.ProgramRunnerUtil` to start debug configs.
- In `steroid_execute_code`, do not use `return@executeSteroidCode` or `return@executeSuspend`; use plain `return`.

## API Contracts (2025.3+)

- Run/debug launch: use `ProgramRunnerUtil.executeConfiguration(settings, executor)` with a real `Executor` (`DefaultDebugExecutor.getDebugExecutorInstance()` or `ExecutorRegistry.getInstance().getExecutorById(...)`).
- Run config creation: prefer `RunManager.createConfiguration(name, factory)` then `RunManager.addConfiguration(settings)`; choose storage via `settings.storeInDotIdeaFolder()` or `settings.storeInLocalWorkspace()` before add.
- Breakpoints: use `XDebuggerUtil.getInstance().toggleLineBreakpoint(project, file, line)` instead of internal/impl helpers.
- Session control: use `XDebuggerManager.getInstance(project).currentSession`, then `pause()`, `resume()`, `stepOver(...)`, `stop()`.
- Expression evaluation is callback-based: `XDebuggerEvaluator.evaluate(String|XExpression, XEvaluationCallback, XSourcePosition?)` returns `Unit` and does not return the value directly.
- PSI/VFS lookups (for example `FilenameIndex`, `PsiManager`, documents) must run in `readAction { ... }`.

## Failure-Recovery Pattern

When a debugger script fails with unresolved imports/APIs or runtime setup errors:

1. Stop and split work into short stateful calls (breakpoint setup -> debug launch -> inspect).
2. Reuse existing debugger resources instead of inventing large custom scripts.
3. If debug setup still fails, do one final source-level diagnosis call and report root cause clearly.
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
- [Debug Run Configuration](mcp-steroid://debugger/debug-run-configuration) - Start debugging
- [Demo Debug Test](mcp-steroid://debugger/demo-debug-test) - End-to-end debug + test results demo
- [Debug Session Control](mcp-steroid://debugger/debug-session-control) - Pause, resume, stop
- [List Threads](mcp-steroid://debugger/debug-list-threads) - Inspect execution stacks
- [Thread Dump](mcp-steroid://debugger/debug-thread-dump) - Generate thread dumps

### Related Example Guides
- [Test Examples](mcp-steroid://test/overview) - Test execution and result inspection
- [IDE Examples](mcp-steroid://ide/overview) - IDE power operations
- [LSP Examples](mcp-steroid://lsp/overview) - Code navigation and intelligence
- [Open Project Examples](mcp-steroid://open-project/overview) - Project opening workflows
