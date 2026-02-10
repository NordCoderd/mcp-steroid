import com.intellij.xdebugger.XDebuggerManager
import com.intellij.xdebugger.evaluation.XDebuggerEvaluator
import com.intellij.xdebugger.frame.XFullValueEvaluator
import com.intellij.xdebugger.frame.XValue
import com.intellij.xdebugger.frame.XValueNode
import com.intellij.xdebugger.frame.XValuePlace
import com.intellij.xdebugger.frame.presentation.XValuePresentation
import com.intellij.xdebugger.impl.breakpoints.XExpressionImpl
import com.intellij.xdebugger.impl.ui.tree.nodes.XValuePresentationUtil
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.future.await
import kotlinx.coroutines.withTimeout
import javax.swing.Icon
import kotlin.time.Duration.Companion.seconds

// --- Reusable helper: copy this into your scripts ---

/**
 * Evaluates a debugger expression and returns its formatted string value.
 *
 * Phase 1: Uses CompletableDeferred to convert the callback-based
 *   XDebuggerEvaluator.evaluate() into a suspend call.
 * Phase 2: Awaits XValue.isReady (CompletableFuture) to ensure the value
 *   descriptor is fully initialized before requesting presentation.
 *   This prevents the "Collecting data..." race that occurs when
 *   computePresentation runs before the JDI value is fetched.
 * Phase 3: Requests the formatted text via computePresentation callback.
 */
suspend fun evaluateExpression(
    evaluator: XDebuggerEvaluator,
    expr: String,
    sourcePosition: com.intellij.xdebugger.XSourcePosition? = null,
    timeout: Long = 30
): String {
    // Phase 1: Evaluate expression to get XValue
    val valueDeferred = CompletableDeferred<XValue>()
    evaluator.evaluate(XExpressionImpl.fromText(expr), object : XDebuggerEvaluator.XEvaluationCallback {
        override fun evaluated(value: XValue) {
            valueDeferred.complete(value)
        }
        override fun errorOccurred(msg: String) {
            valueDeferred.completeExceptionally(Exception(msg))
        }
    }, sourcePosition)

    val value = try {
        withTimeout(timeout.seconds) { valueDeferred.await() }
    } catch (e: Exception) {
        return "ERR: ${e.message}"
    }

    // Phase 2: Wait for the value descriptor to be fully initialized.
    // XValue.isReady() returns CompletableFuture<Void> that completes once
    // the underlying JavaValue's ValueDescriptorImpl.calcValue() finishes.
    // Without this, computePresentation may return "Collecting data..."
    // because the JDI communication hasn't completed yet.
    // Uses kotlinx.coroutines.future.await() for cancellation-safe waiting.
    try {
        withTimeout(timeout.seconds) { value.isReady.await() }
    } catch (e: Exception) {
        return "ERR: Value not ready - ${e.message}"
    }

    // Phase 3: Compute presentation to get formatted text
    val presentationDeferred = CompletableDeferred<String>()
    value.computePresentation(object : XValueNode {
        override fun setPresentation(icon: Icon?, type: String?, text: String, hasChildren: Boolean) {
            presentationDeferred.complete(text)
        }
        override fun setPresentation(icon: Icon?, pres: XValuePresentation, hasChildren: Boolean) {
            presentationDeferred.complete(XValuePresentationUtil.computeValueText(pres))
        }
        override fun setFullValueEvaluator(e: XFullValueEvaluator) {}
        override fun isObsolete() = false
    }, XValuePlace.TOOLTIP)

    return try {
        withTimeout(timeout.seconds) { presentationDeferred.await() }
    } catch (e: Exception) {
        "ERR: Presentation timeout - ${e.message}"
    }
}
// --- End of helper ---

val session = XDebuggerManager.getInstance(project).currentSession
    ?: error("No debug session. Start one first.")
check(session.isSuspended) { "Session is not suspended. Wait for breakpoint hit." }

val frame = session.currentStackFrame
    ?: error("No current stack frame")
val evaluator = frame.evaluator
    ?: error("No evaluator for current frame")
val pos = frame.sourcePosition

// Evaluate variables at the current breakpoint
val playersValue = evaluateExpression(evaluator, "players", pos)
println("players =", playersValue)

val sizeValue = evaluateExpression(evaluator, "players.size", pos)
println("players.size =", sizeValue)

// Evaluate complex expressions
val sortedValue = evaluateExpression(evaluator, "players.sortedByDescending { it.score }", pos)
println("sorted result =", sortedValue)
