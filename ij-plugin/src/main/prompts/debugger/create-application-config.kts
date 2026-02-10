import com.intellij.execution.RunManager
import com.intellij.execution.application.ApplicationConfiguration
import com.intellij.execution.application.ApplicationConfigurationType
import com.intellij.openapi.module.ModuleManager

val mainClassName = "com.example.MainKt"  // TODO: Set your main class FQN
val configName = "MyApp"  // TODO: Set configuration name

val runManager = RunManager.getInstance(project)

// Check if configuration already exists
val existing = runManager.findConfigurationByName(configName)
if (existing != null) {
    println("Run configuration already exists:", configName)
    return
}

val factory = ApplicationConfigurationType.getInstance().configurationFactories.first()
val settings = runManager.createConfiguration(configName, factory)
val config = settings.configuration as ApplicationConfiguration

config.mainClassName = mainClassName

// Set module (pick the first available or filter by name)
val modules = ModuleManager.getInstance(project).modules
val module = modules.firstOrNull { it.name.endsWith(".main") }
    ?: modules.firstOrNull()
if (module != null) {
    config.setModule(module)
}

settings.storeInDotIdeaFolder()
runManager.addConfiguration(settings)
runManager.selectedConfiguration = settings

println("Created run configuration:", configName, "main:", mainClassName)
