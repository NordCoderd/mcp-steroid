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

- Stop debug sessions when done: use debug-session-control or
  withContext(Dispatchers.EDT) { XDebuggerManager.getInstance(project).debugSessions.forEach { it.stop() } }
- Line numbers are 1-based in these examples (match the editor gutter).
- `debug-list-threads` and `debug-thread-dump` require a suspended session.
- Use `DefaultDebugExecutor` and `ProgramRunnerUtil` to start debug configs.

---

## Related Resources

### Skill Guides
- [IntelliJ API Power User Guide](mcp-steroid://skill/intellij-api-poweruser-guide) - Core API reference and patterns
- [Debugger Skill Guide](mcp-steroid://skill/debugger-guide) - Essential debugger knowledge and workflows
- [Test Runner Skill Guide](mcp-steroid://skill/test-runner-guide) - Test execution

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
