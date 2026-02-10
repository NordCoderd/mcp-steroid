Evaluate Expression at Breakpoint

Evaluate a variable or expression when the debugger is suspended at a breakpoint.
Includes a reusable `evaluateExpression()` helper and a simpler JDI-based `evaluateJavaExpression()` alternative.

Prerequisites: a suspended debug session (use `wait-for-suspend` first).

IntelliJ APIs: XDebuggerManager, XDebuggerEvaluator, XValue, XValueNode, CompletableDeferred
