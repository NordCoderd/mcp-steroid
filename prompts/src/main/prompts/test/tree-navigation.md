Test: Test Tree Navigation

This example shows how to navigate the test tree structure,

```kotlin
import com.intellij.execution.ui.RunContentManager
import com.intellij.execution.testframework.sm.runner.ui.SMTRunnerConsoleView
import com.intellij.execution.testframework.AbstractTestProxy

// Recursive function to print test tree
fun printTestTree(proxy: AbstractTestProxy, indent: String = "", isLast: Boolean = true) {
    // Determine status icon
    val status = when {
        proxy.isPassed -> "✓"
        proxy.isDefect -> "✗"
        proxy.isIgnored -> "○"
        proxy.isInProgress -> "→"
        else -> "?"
    }

    // Determine tree line characters
    val branch = if (indent.isEmpty()) "" else if (isLast) "└─ " else "├─ "

    // Print test node
    val duration = proxy.duration ?: 0L
    val durationStr = if (duration > 0) " (${duration}ms)" else ""
    println("$indent$branch$status ${proxy.name}$durationStr")

    // Recurse into children
    val children = proxy.children
    if (children.isNotEmpty()) {
        val childIndent = indent + if (isLast) "   " else "│  "
        children.forEachIndexed { index, child ->
            val childIsLast = index == children.size - 1
            printTestTree(child, childIndent, childIsLast)
        }
    }
}

// Get test results
val manager = RunContentManager.getInstance(project)
val descriptor = manager.allDescriptors.lastOrNull()

if (descriptor == null) {
    println("No test execution found")
    return
}

val console = descriptor.executionConsole as? SMTRunnerConsoleView
if (console == null) {
    println("Not a test execution")
    return
}

val resultsForm = console.resultsViewer
val rootProxy = resultsForm.testsRootNode

// Print tree
println("Test Tree: ${descriptor.displayName}")
println("═══════════════════════════════════════")
println()

printTestTree(rootProxy)

println()
println("═══════════════════════════════════════")
println("Legend:")
println("  ✓ = Passed")
println("  ✗ = Failed")
println("  ○ = Ignored")
println("  → = In Progress")
```

# See also

Related IDE operations:
- [Run Configuration](mcp-steroid://ide/run-configuration) - List and execute run configs

Skill guides:
- [Test Runner Skill Guide](mcp-steroid://prompt/test-skill) - Essential test knowledge
