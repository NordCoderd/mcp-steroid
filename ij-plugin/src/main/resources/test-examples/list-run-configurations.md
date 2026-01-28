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

/**
 * ## See Also
 *
 * Related test examples:
 * - [Run Tests](mcp-steroid://test/run-tests) - Execute test configuration
 * - [Wait for Completion](mcp-steroid://test/wait-for-completion) - Poll test status
 * - [Inspect Test Results](mcp-steroid://test/inspect-test-results) - Access results
 * - [Test Tree Navigation](mcp-steroid://test/test-tree-navigation) - Navigate test hierarchy
 *
 * Related IDE operations:
 * - [Run Configuration](mcp-steroid://ide/run-configuration) - List and execute run configs
 *
 * Related debugger operations:
 * - [Debug Run Configuration](mcp-steroid://debugger/debug-run-configuration) - Start debugging
 *
 * Overview resources:
 * - [Test Examples Overview](mcp-steroid://test/overview) - Complete test execution guide
 * - [Test Runner Skill Guide](mcp-steroid://skill/test-runner-guide) - Essential test knowledge
 */
