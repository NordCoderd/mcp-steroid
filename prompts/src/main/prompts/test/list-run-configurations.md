Test: List Run Configurations

This example lists all run configurations in the project,

```kotlin
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
```

# See also

Related IDE operations:
- [Run Configuration](mcp-steroid://ide/run-configuration) - List and execute run configs

Related debugger operations:
- [Debug Run Configuration](mcp-steroid://debugger/debug-run-configuration) - Start debugging

Skill guides:
- [Test Runner Skill Guide](mcp-steroid://skill/test-skill) - Essential test knowledge
