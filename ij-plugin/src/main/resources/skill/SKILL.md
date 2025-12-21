---
name: intellij-mcp-steroid
description: Execute Kotlin code directly in IntelliJ IDEA's runtime with full access to IntelliJ Platform APIs. Use when you need to search code, run refactorings, access PSI (parsed code model), query project structure, run inspections, or perform any IDE operation. Prefer this over file-based operations - the IDE has indexed everything.
license: Apache-2.0
compatibility: Requires IntelliJ IDEA 2025.2+ with the MCP Steroid plugin installed
metadata:
  author: jonnyzzz
  version: "1.0"
  category: development
---

# IntelliJ MCP Steroid - IDE API Access for AI Agents

Execute Kotlin code directly in IntelliJ IDEA's runtime with full access to the IntelliJ Platform API.

## Quickstart Flow

```
1. steroid_list_projects → get list of open projects
2. Pick a project_name from the list
3. steroid_execute_code → run Kotlin code with that project
4. steroid_execute_feedback → report success/failure for tracking
```

**Example session:**
```
→ steroid_list_projects
← {"projects":[{"name":"my-app","path":"/path/to/my-app"}]}

→ steroid_execute_code(project_name="my-app", code="execute { println(project.name) }", ...)
← "my-app"

→ steroid_execute_feedback(project_name="my-app", task_id="...", execution_id="...", success_rating=1.0, explanation="Got project name")
```

## When to Use This Skill

**ALWAYS prefer IntelliJ APIs over file-based operations:**

| Instead of...                   | Use IntelliJ API               |
|---------------------------------|--------------------------------|
| Reading files with `cat`/`read` | VFS and PSI APIs               |
| Searching with `grep`/`find`    | Find Usages, Structural Search |
| Manual text replacement         | Automated refactorings         |
| Guessing code structure         | Query project model directly   |

The IDE has indexed everything. It knows the code better than any file search.

## Available Tools

### `steroid_list_projects`
List all open projects. Returns project names for use with `steroid_execute_code`.

### `steroid_execute_code`
Execute Kotlin code in IntelliJ's runtime.

**Parameters:**
- `project_name` (required): Target project from `steroid_list_projects`
- `code` (required): Kotlin code with `execute { }` block
- `reason` (required): Human-readable explanation
- `task_id` (required): Group related executions
- `timeout` (optional): Timeout in seconds (default: 60)

### `steroid_execute_feedback`
Rate execution results. Use after `steroid_execute_code`.

## Critical Rules

### 1. Execute Block is a SUSPEND Function
```kotlin
execute {
    // This is a coroutine - use suspend APIs!
    waitForSmartMode()  // suspend function - works directly
    delay(1000)         // coroutine delay - works directly
}
```
**NEVER use `runBlocking`** - it causes deadlocks.

### 2. IMPORTS Must Be OUTSIDE Execute Block

**WRONG:**
```kotlin
execute {
    import com.intellij.psi.PsiManager  // ERROR!
}
```

**CORRECT:**
```kotlin
import com.intellij.psi.PsiManager
import com.intellij.openapi.application.readAction

execute {
    val file = readAction { PsiManager.getInstance(project).findFile(vf) }
}
```

### 3. Read/Write Actions for PSI/VFS

```kotlin
import com.intellij.openapi.application.readAction
import com.intellij.openapi.application.writeAction

execute {
    // Reading PSI/VFS/indices
    val data = readAction {
        PsiManager.getInstance(project).findFile(virtualFile)
    }

    // Modifying PSI/VFS/documents
    writeAction {
        document.setText("new content")
    }
}
```

## Script Template

```kotlin
// 1. IMPORTS - Always outside execute block
import com.intellij.openapi.application.readAction
import com.intellij.psi.PsiManager

// 2. EXECUTE BLOCK
execute {
    // 3. Wait for indexing if needed
    waitForSmartMode()

    // 4. Use IntelliJ APIs
    val result = readAction {
        // PSI/VFS operations here
    }

    // 5. Output results
    println(result)
}
```

## Context Available in Execute Block

```kotlin
execute {
    // Properties
    project      // IntelliJ Project instance
    params       // Tool parameters (JsonElement)
    disposable   // For resource cleanup
    isDisposed   // Check if disposed

    // Output methods
    println("Hello", 42, "world")      // Space-separated output
    printJson(object { val x = 1 })    // Pretty JSON
    printException("Failed", exception) // Error with stack trace (recommended for errors)
    progress("Step 1 of 3...")         // Progress (throttled 1/sec)

    // IDE utilities
    waitForSmartMode()  // Wait for indexing
}
```

## Common Patterns

### Get Project Info
```kotlin
execute {
    println("Project: ${project.name}")
    println("Base path: ${project.basePath}")
}
```

### Get IDE Log Path
```kotlin
execute {
    val logPath = com.intellij.openapi.application.PathManager.getLogPath()
    println("Log: $logPath/idea.log")
}
```

### List Plugins
```kotlin
execute {
    com.intellij.ide.plugins.PluginManagerCore.getLoadedPlugins()
        .filter { it.isEnabled }
        .forEach { println("${it.name}: ${it.version}") }
}
```

### Find Plugin by ID
```kotlin
execute {
    val plugin = com.intellij.ide.plugins.PluginManagerCore.loadedPlugins
        .find { it.pluginId.idString == "org.jetbrains.kotlin" }
    println("Kotlin plugin: ${plugin?.version}")
}
```

### List Extension Points
```kotlin
execute {
    project.extensionArea.extensionPoints
        .filter { it.name.contains("kotlin", ignoreCase = true) }
        .forEach { println("${it.name}: ${it.extensionList.size} extensions") }
}
```

### Navigate Project Files
```kotlin
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.vfs.VfsUtil

execute {
    ProjectRootManager.getInstance(project).contentRoots.forEach { root ->
        println("Root: ${root.path}")
        VfsUtil.iterateChildrenRecursively(root, null) { file ->
            if (file.extension == "kt") println("  ${file.path}")
            true
        }
    }
}
```

### Open Another Project (CAUTION: opens new window)
```kotlin
import com.intellij.ide.impl.OpenProjectTask
import com.intellij.openapi.project.ex.ProjectManagerEx
import java.nio.file.Path

execute {
    val path = Path.of("/path/to/project")
    ProjectManagerEx.getInstanceEx().openProjectAsync(path, OpenProjectTask { })
}
```

### Restart IDE (CAUTION: destructive operation)
```kotlin
execute {
    com.intellij.openapi.application.ApplicationManager.getApplication().restart()
}
```

## Complete End-to-End PSI Example

This example finds a Kotlin class, gets its methods, and prints their signatures:

```kotlin
import com.intellij.openapi.application.readAction
import com.intellij.psi.search.GlobalSearchScope
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.idea.stubindex.KotlinClassShortNameIndex

execute {
    waitForSmartMode()

    readAction {
        // Find all classes named "MyService" in the project
        val scope = GlobalSearchScope.projectScope(project)
        val classes = KotlinClassShortNameIndex.get("MyService", project, scope)

        if (classes.isEmpty()) {
            println("No class named 'MyService' found")
            return@readAction
        }

        classes.forEach { ktClass ->
            println("Class: ${ktClass.fqName}")
            println("File: ${ktClass.containingFile.virtualFile.path}")

            // List all methods
            ktClass.declarations.filterIsInstance<org.jetbrains.kotlin.psi.KtNamedFunction>()
                .forEach { method ->
                    val params = method.valueParameters.joinToString { "${it.name}: ${it.typeReference?.text}" }
                    val returnType = method.typeReference?.text ?: "Unit"
                    println("  fun ${method.name}($params): $returnType")
                }
        }
    }
}
```

## Find Usages (Complete Example)

```kotlin
import com.intellij.openapi.application.readAction
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.ReferencesSearch
import org.jetbrains.kotlin.idea.stubindex.KotlinClassShortNameIndex

execute {
    waitForSmartMode()

    readAction {
        // First find the class
        val scope = GlobalSearchScope.projectScope(project)
        val classes = KotlinClassShortNameIndex.get("MyService", project, scope)
        val targetClass = classes.firstOrNull()

        if (targetClass == null) {
            println("Class not found")
            return@readAction
        }

        // Find all usages using findAll() (returns a Collection)
        val usages = ReferencesSearch.search(targetClass, scope).findAll()

        println("Found ${usages.size} usages of ${targetClass.name}:")
        usages.forEach { ref ->
            val file = ref.element.containingFile.virtualFile.path
            val offset = ref.element.textOffset
            println("  $file:$offset")
        }
    }
}
```

## Actions

### Find Action by ID
```kotlin
import com.intellij.openapi.actionSystem.ActionManager

execute {
    val action = ActionManager.getInstance().getAction("GotoFile")
    println("Action: ${action?.templatePresentation?.text}")
}
```

### List All Actions
```kotlin
import com.intellij.openapi.actionSystem.ActionManager

execute {
    ActionManager.getInstance().getActionIdList("")
        .filter { it.contains("Goto", ignoreCase = true) }
        .take(20)
        .forEach { println(it) }
}
```

## Reflection for API Discovery

```kotlin
execute {
    try {
        val clazz = Class.forName("com.intellij.openapi.project.Project")
        clazz.methods
            .filter { it.parameterCount == 0 }
            .take(20)
            .forEach { println("${it.name}(): ${it.returnType.simpleName}") }
    } catch (e: ClassNotFoundException) {
        println("Class not found")
    }
}
```

## Error Handling

Use `printException` for errors - it includes the stack trace in the output:

```kotlin
execute {
    try {
        // risky operation
    } catch (e: Exception) {
        printException("Operation failed", e)  // Recommended - includes stack trace in output
    }
}
```

## Best Practices

1. **Call `waitForSmartMode()` before PSI operations** - indexing must complete first
2. **Use `readAction { }` for all PSI/VFS reads** - even simple property access
3. **Use `writeAction { }` for all PSI/VFS modifications** - always
4. **Keep imports outside `execute { }`** - imports inside won't compile
5. **Prefer Kotlin coroutine APIs** - you're in a suspend context
6. **Use meaningful `task_id`** - groups related executions
7. **Report progress** - `progress("Step 1 of 5...")`
8. **Use `printException` for errors** - better than printStackTrace
9. **Use reflection for discovery** - explore unknown APIs
10. **Prefer IntelliJ APIs over file operations** - IDE has indexed everything

## Troubleshooting

### Check if Server is Running
The MCP server runs inside IntelliJ. To verify:
1. Open IntelliJ IDEA with the MCP Steroid plugin installed
2. Open any project
3. Check `.idea/mcp-steroids.txt` in the project folder for the server URL
4. The server binds to port 63150 by default (configurable via `mcp.steroids.server.port` registry key)

### Endpoints
- `/` - Returns this SKILL.md content
- `/skill.md` - Same as above
- `/mcp` - MCP protocol endpoint for tool calls
- `/.well-known/mcp.json` - MCP server discovery

### Common Issues
- **"Project not found"** - Run `steroid_list_projects` first to get exact project names
- **No output from execute** - Make sure to call `println()` or `printJson()` to see results
- **Timeout** - Increase `timeout` parameter (default 60 seconds)
- **Script errors** - Check imports are outside `execute { }` block

## Summary

The MCP Steroid server gives you **direct access to IntelliJ's runtime**:

- Query the project model
- Invoke any IntelliJ API
- Run refactorings
- Execute actions
- Inspect plugins
- Access PSI (parsed code)

**The execute block is a suspend function.** Use coroutine APIs directly.

**Don't settle for file-level operations when you have IDE-level access.**
