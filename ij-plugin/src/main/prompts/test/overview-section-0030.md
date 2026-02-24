
### Pattern 3: Navigate Test Tree Recursively

```kotlin
fun printTestTree(proxy: AbstractTestProxy, indent: String = "") {
    val status = when {
        proxy.isPassed -> "✓"
        proxy.isDefect -> "✗"
        proxy.isIgnored -> "○"
        proxy.isInProgress -> "→"
        else -> "?"
    }

    println("$indent$status ${proxy.name} (${proxy.duration}ms)")

    proxy.children.forEach { child ->
        printTestTree(child, "$indent  ")
    }
}

val manager = RunContentManager.getInstance(project)
val descriptor = manager.allDescriptors.lastOrNull()
val console = descriptor?.executionConsole as? SMTRunnerConsoleView
val rootProxy = console?.resultsViewer?.testsRootNode

if (rootProxy != null) {
    printTestTree(rootProxy)
}
```
