/**
 * IDE: Inspection Summary
 *
 * This example lists enabled inspections for the current project.
 *
 * IntelliJ API used:
 * - InspectionProjectProfileManager
 * - InspectionProfile
 * - ScopeToolState
 *
 * Parameters to customize:
 * - maxInspections: Limit the number of inspections shown
 *
 * Output: Enabled inspection summary
 */

import com.intellij.profile.codeInspection.InspectionProjectProfileManager

execute {
    // Configuration - modify these for your use case
    val maxInspections = 50

    waitForSmartMode()

    val result = readAction {
        val profile = InspectionProjectProfileManager.getInstance(project).currentProfile
        @Suppress("UNCHECKED_CAST")
        val tools = profile.getAllEnabledInspectionTools(project) as List<Any?>
        val states = tools.mapNotNull { item ->
            when (item) {
                is com.intellij.codeInspection.ex.ScopeToolState -> item
                is com.intellij.codeInspection.ex.Tools -> item.defaultState
                else -> null
            }
        }

        buildString {
            appendLine("Enabled inspections (${states.size}):")
            appendLine()
            states.take(maxInspections).forEach { state ->
                val tool = state.tool
                val shortName = tool.shortName
                val displayName = tool.displayName
                val group = tool.groupDisplayName
                appendLine("  - $shortName: $displayName ($group)")
            }
            if (states.size > maxInspections) {
                appendLine()
                appendLine("... and ${states.size - maxInspections} more inspections")
            }
        }
    }

    println(result)
}
