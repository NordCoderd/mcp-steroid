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
