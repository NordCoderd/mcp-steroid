Evaluate Expression at Breakpoint

Evaluate a variable or expression when the debugger is suspended at a breakpoint.
Includes a reusable `evaluateExpression()` helper that handles the two-phase rendering pipeline:

1. **Phase 1**: Awaits `XValue.isReady` (basic value descriptor initialization)
2. **Phase 2**: Requests presentation via `computePresentation()`
3. **Phase 3**: For complex objects (collections, custom toString()), polls until the async
   BatchEvaluator completes and "Collecting data..." is replaced with the final value.

This prevents the "Collecting data..." race that occurs after step-over operations when
the suspend context changes and toString() is invoked asynchronously via JDI.

Prerequisites: a suspended debug session (use `wait-for-suspend` first).

IntelliJ APIs: XDebuggerManager, XDebuggerEvaluator, XValue, XValueNode, CompletableDeferred, kotlinx.coroutines.future.await
