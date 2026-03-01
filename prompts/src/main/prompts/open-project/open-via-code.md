Open Project via IntelliJ APIs

This script demonstrates how to open a project programmatically using

```kotlin
import com.intellij.ide.impl.OpenProjectTask
import com.intellij.ide.trustedProjects.TrustedProjects
import com.intellij.openapi.project.ex.ProjectManagerEx
import java.nio.file.Path
import java.nio.file.Files

// === CONFIGURATION ===
// Change this to your project path
val projectPath = Path.of("/path/to/your/project")

// Set to true to trust the project (skips trust dialog)
val trustProject = true

// Set to true to always open in a new window
val forceNewFrame = false

// === VALIDATION ===
if (!Files.exists(projectPath)) {
    println("ERROR: Project path does not exist: $projectPath")
    return
}

if (!Files.isDirectory(projectPath)) {
    println("ERROR: Project path is not a directory: $projectPath")
    return
}

// === TRUST PROJECT ===
if (trustProject) {
    println("Trusting project path: $projectPath")
    TrustedProjects.setProjectTrusted(projectPath, isTrusted = true)
    println("Project trusted successfully")
}

// === OPEN PROJECT ===
println("Opening project: $projectPath")

// Create the OpenProjectTask with our options
val task = OpenProjectTask {
    forceOpenInNewFrame = forceNewFrame
    showWelcomeScreen = false
}

// Open the project asynchronously
// Note: This is a suspend function; the script body runs in a suspend context.
// which is already a coroutine context
try {
    val openedProject = ProjectManagerEx.getInstanceEx().openProjectAsync(projectPath, task)

    if (openedProject != null) {
        println("Project opened successfully!")
        println("  Name: ${openedProject.name}")
        println("  Path: ${openedProject.basePath}")
        println("  Is open: ${openedProject.isOpen}")
    } else {
        println("Project opening returned null (may have been cancelled or failed)")
        println("Check the IDE logs for more details")
    }
} catch (e: Exception) {
    printException("Failed to open project", e)
}

// === VERIFY ===
// List all currently open projects
println("\nCurrently open projects:")
com.intellij.openapi.project.ProjectManager.getInstance().openProjects.forEach { proj ->
    println("  - ${proj.name} (${proj.basePath})")
}
```

# See also

Related MCP tools:
- `steroid_open_project` - Tool for opening projects via MCP
- `steroid_list_projects` - List all open projects
- `steroid_list_windows` - Check project initialization status

- [IntelliJ API Power User Guide](mcp-steroid://skill/skill) - Core API patterns
