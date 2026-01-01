/**
 * IDE: Project Dependencies
 *
 * This example summarizes module dependencies and libraries in a project.
 *
 * IntelliJ API used:
 * - ModuleManager - Enumerate modules
 * - ModuleRootManager - Access order entries
 * - OrderEnumerator - Traverse dependencies
 *
 * Parameters to customize:
 * - maxEntries: Limit the number of dependencies per module
 *
 * Output: Module dependency summary
 */

import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.roots.OrderEntry

execute {
    // Configuration - modify these for your use case
    val maxEntries = 20

    waitForSmartMode()

    val result = readAction {
        val modules = ModuleManager.getInstance(project).modules
        buildString {
            appendLine("Project Dependencies (${modules.size} modules)")
            appendLine()
            modules.forEach { module ->
                appendLine("Module: ${module.name}")
                val entries = mutableListOf<OrderEntry>()
                ModuleRootManager.getInstance(module).orderEntries().forEach { entry ->
                    entries.add(entry)
                    true
                }
                entries.take(maxEntries).forEach { entry ->
                    appendLine("  - ${entry.presentableName}")
                }
                if (entries.size > maxEntries) {
                    appendLine("  ... and ${entries.size - maxEntries} more")
                }
                appendLine()
            }
        }
    }

    println(result)
}
