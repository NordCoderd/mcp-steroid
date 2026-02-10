Evaluate Expression at Breakpoint

Evaluate a variable or expression when the debugger is suspended at a breakpoint.
Includes a reusable `evaluateExpression()` helper that awaits `XValue.isReady`
(CompletableFuture) before requesting presentation, preventing the "Collecting data..." race.

Prerequisites: a suspended debug session (use `wait-for-suspend` first).

IntelliJ APIs: XDebuggerManager, XDebuggerEvaluator, XValue, XValueNode, CompletableDeferred, kotlinx.coroutines.future.await
