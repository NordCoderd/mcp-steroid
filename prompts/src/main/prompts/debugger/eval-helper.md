Reusable Expression Evaluation Helper

Copy this eval() function into your debugger scripts to evaluate expressions at a breakpoint.

```kotlin
import com.intellij.xdebugger.evaluation.XDebuggerEvaluator
import com.intellij.xdebugger.frame.XFullValueEvaluator
import com.intellij.xdebugger.frame.XValue
import com.intellij.xdebugger.frame.XValueNode
import com.intellij.xdebugger.frame.XValuePlace
import com.intellij.xdebugger.frame.presentation.XValuePresentation
import com.intellij.xdebugger.impl.breakpoints.XExpressionImpl
import com.intellij.xdebugger.impl.ui.tree.nodes.XValuePresentationUtil
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout
import javax.swing.Icon
import kotlin.time.Duration.Companion.seconds

/**
 * Evaluates a debugger expression and returns its formatted string value.
 *
 * Bridges two async callback APIs using CompletableDeferred:
 * 1. XDebuggerEvaluator.evaluate() -> XValue via XEvaluationCallback
 * 2. XValue.computePresentation() -> formatted text via XValueNode callback
 *
 * IMPORTANT: Do NOT await value.isReady before computePresentation.
 * In Rider/DotNetValue, isReady only completes INSIDE computePresentation's async
 * coroutine — awaiting it first causes a 30-second timeout deadlock.
 * The retry loop below handles JVM "Collecting data..." cases instead.
 *
 * IMPORTANT: All callback overrides use block bodies { }, NOT expression bodies.
 * Expression body `= deferred.complete(value)` causes type mismatch (Boolean vs Unit).
 */
suspend fun eval(
    evaluator: XDebuggerEvaluator,
    expr: String,
    pos: com.intellij.xdebugger.XSourcePosition? = null,
    timeout: Long = 30
): String {
    val valueDeferred = CompletableDeferred<XValue>()
    evaluator.evaluate(
        XExpressionImpl.fromText(expr),
        object : XDebuggerEvaluator.XEvaluationCallback {
            override fun evaluated(value: XValue) { valueDeferred.complete(value) }
            override fun errorOccurred(msg: String) { valueDeferred.completeExceptionally(Exception(msg)) }
        },
        pos
    )
    val value = try {
        withTimeout(timeout.seconds) { valueDeferred.await() }
    } catch (e: Exception) {
        return "ERR: ${e.message}"
    }

    // Call computePresentation directly — do NOT await value.isReady first (see above).
    val presDeferred = CompletableDeferred<String>()
    value.computePresentation(object : XValueNode {
        override fun setPresentation(icon: Icon?, type: String?, text: String, hasChildren: Boolean) {
            presDeferred.complete(text)
        }
        override fun setPresentation(icon: Icon?, pres: XValuePresentation, hasChildren: Boolean) {
            presDeferred.complete(XValuePresentationUtil.computeValueText(pres))
        }
        override fun setFullValueEvaluator(e: XFullValueEvaluator) {}
        override fun isObsolete() = false
    }, XValuePlace.TOOLTIP)

    val result = try {
        withTimeout(timeout.seconds) { presDeferred.await() }
    } catch (e: Exception) {
        return "ERR: Presentation timeout - ${e.message}"
    }

    if (result.contains("Collecting data")) {
        repeat(10) {
            delay(200)
            val retry = CompletableDeferred<String>()
            value.computePresentation(object : XValueNode {
                override fun setPresentation(icon: Icon?, type: String?, text: String, hasChildren: Boolean) {
                    retry.complete(text)
                }
                override fun setPresentation(icon: Icon?, pres: XValuePresentation, hasChildren: Boolean) {
                    retry.complete(XValuePresentationUtil.computeValueText(pres))
                }
                override fun setFullValueEvaluator(e: XFullValueEvaluator) {}
                override fun isObsolete() = false
            }, XValuePlace.TOOLTIP)
            val text = try { withTimeout(5.seconds) { retry.await() } } catch (_: Exception) { return result }
            if (!text.contains("Collecting data")) return text
        }
    }
    return result
}
```

```kotlin
// Usage: get the current debug session and evaluate an expression.
import com.intellij.xdebugger.XDebuggerManager
import com.intellij.xdebugger.evaluation.XDebuggerEvaluator
import com.intellij.xdebugger.frame.XValue
import kotlinx.coroutines.CompletableDeferred
import kotlin.time.Duration.Companion.seconds

val session = XDebuggerManager.getInstance(project).currentSession ?: error("No session")
val frame = session.currentStackFrame ?: error("No frame")
val evaluator = frame.evaluator ?: error("No evaluator")

// Evaluate an expression using callback → coroutine bridge
val deferred = CompletableDeferred<XValue>()
readAction {
    evaluator.evaluate("myVariable", object : XDebuggerEvaluator.XEvaluationCallback {
        override fun evaluated(result: XValue) { deferred.complete(result) }
        override fun errorOccurred(errorMessage: String) { deferred.completeExceptionally(RuntimeException(errorMessage)) }
    }, frame.sourcePosition)
}
val result = withTimeout(10.seconds) { deferred.await() }
println("myVariable = $result")

// Language-specific expressions:
//   Kotlin/Java: players.sortedByDescending { it.score }
//   C#/.NET:     players.OrderByDescending(p => p.Score).ToList()
//
// After step-over, get fresh evaluator from session.currentStackFrame.
```

# See also

Related debugger operations:
- [Evaluate Expression](mcp-steroid://debugger/evaluate-expression) - Full evaluation example with helper
- [Step Over](mcp-steroid://debugger/step-over) - Step then re-evaluate
- [Wait for Suspend](mcp-steroid://debugger/wait-for-suspend) - Wait for breakpoint hit

Overview resources:
- [Debugger Skill Guide](mcp-steroid://skill/debugger-skill) - Essential debugger knowledge
