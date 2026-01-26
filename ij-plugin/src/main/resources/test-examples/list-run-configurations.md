/**
 * Test: List Run Configurations
 *
 * This example lists all run configurations in the project,
 * showing their names and types.
 *
 * IntelliJ API used:
 * - RunManager - Access run configurations
 * - RunnerAndConfigurationSettings - Configuration settings
 *
 * Output: List of all run configurations with their types
 */

import com.intellij.execution.RunManager

execute {
    waitForSmartMode()

    val manager = RunManager.getInstance(project)
    val allSettings = manager.allSettings

    println("Run Configurations (${allSettings.size}):")
    println()

    allSettings.forEach { setting ->
        val typeName = setting.type.displayName
        val configName = setting.name
        val isTemplate = setting.isTemplate
        val isTemporary = setting.isTemporary

        val flags = buildList {
            if (isTemplate) add("template")
            if (isTemporary) add("temporary")
        }.joinToString(", ")

        val flagsStr = if (flags.isNotEmpty()) " [$flags]" else ""

        println("  • $configName ($typeName)$flagsStr")
    }

    // Show selected configuration
    val selected = manager.selectedConfiguration
    if (selected != null) {
        println()
        println("Selected: ${selected.name}")
    }
}
