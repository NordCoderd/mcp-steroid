Coding with IntelliJ: Common Patterns

Common patterns: project info, plugin discovery, file navigation, module inspection, and VFS utilities.

## Common Patterns

### Get Project Info
```kotlin
println("Project: ${project.name}")
println("Base path: ${project.basePath}")
```

### Get IDE Log Path
```text
val logPath = com.intellij.openapi.application.PathManager.getLogPath()
println("Log: $logPath/idea.log")
```

### List Plugins
```kotlin
import com.intellij.ide.plugins.PluginManagerCore

// enabledPlugins lists all currently loaded plugins
PluginManagerCore.getPluginSet().enabledPlugins
    .forEach { println("${it.name}: ${it.version}") }
```

### Find Plugin by ID
```kotlin
import com.intellij.ide.plugins.PluginManagerCore

val plugin = PluginManagerCore.loadedPlugins
    .find { it.pluginId.idString == "org.jetbrains.kotlin" }
println("Kotlin plugin: ${plugin?.version}")
```

### Check Plugin Installed (Before Using Plugin APIs)

> **⚠️ Do NOT call `PluginsAdvertiser.installAndEnable` or any programmatic plugin installer.**
> These APIs change signatures between IntelliJ versions and throw `IllegalArgumentException` /
> `IllegalAccessError` at runtime (2025.x+). Always check first; if not installed, use `required_plugins`
> parameter instead and let the tool system handle it.
```kotlin
import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.openapi.extensions.PluginId

// Check if a plugin is installed (and loaded) — use this BEFORE calling any plugin-specific API
val pluginId = PluginId.getId("com.intellij.database")  // replace with the plugin you need
val installed = PluginManagerCore.isPluginInstalled(pluginId)
val loaded = PluginManagerCore.getPlugin(pluginId) != null
println("Plugin $pluginId: installed=$installed loaded=$loaded")

// If not loaded: do NOT attempt installation. Instead, report the missing plugin ID and stop.
// The steroid_execute_code `required_plugins` parameter is the correct way to declare dependencies.
```

### Navigate Project Files
```kotlin
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.vfs.VfsUtil

// ⚠️ contentRoots accesses the project model — must be inside readAction { }
val roots = readAction { ProjectRootManager.getInstance(project).contentRoots.toList() }
roots.forEach { root ->
    println("Root: ${root.path}")
    VfsUtil.iterateChildrenRecursively(root, null) { file ->
        if (file.extension == "kt") println("  ${file.path}")
        true
    }
}
```

### Check File Type in Project
```kotlin
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.vfs.LocalFileSystem

val vf = LocalFileSystem.getInstance().findFileByPath("/path/to/file.kt")

if (vf != null) {
    val fileIndex = ProjectFileIndex.getInstance(project)

    println("Is in project: ${fileIndex.isInProject(vf)}")
    println("Is in source: ${fileIndex.isInSource(vf)}")
    println("Is in test source: ${fileIndex.isInTestSourceContent(vf)}")
    println("Is in library: ${fileIndex.isInLibraryClasses(vf)}")

    val module = fileIndex.getModuleForFile(vf)
    println("Module: ${module?.name}")
}
```

---
