# Debugger Examples

These resources show how to drive IntelliJ's debugger APIs from `steroid_execute_code`.
Use them as building blocks to set breakpoints, start a debug session, pause/resume,
and inspect threads/stack frames.

Stateful exec_code note: the execute_code tool is stateful, so run these in multiple
short calls (set breakpoints -> start debug -> pause -> inspect) rather than one
long script with waits.

## Available resources

- `intellij://debugger/set-line-breakpoint` - create a line breakpoint
- `intellij://debugger/debug-run-configuration` - start a run configuration in Debug
- `intellij://debugger/debug-session-control` - pause/resume/stop the current session
- `intellij://debugger/debug-list-threads` - list execution stacks (threads)
- `intellij://debugger/debug-thread-dump` - build a basic thread dump from stacks

## Tips

- Line numbers are 1-based in these examples (match the editor gutter).
- `debug-list-threads` and `debug-thread-dump` require a suspended session.
- Use `DefaultDebugExecutor` and `ProgramRunnerUtil` to start debug configs.
