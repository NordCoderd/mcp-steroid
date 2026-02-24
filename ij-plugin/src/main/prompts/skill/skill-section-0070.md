## Services and Components

### Access Project Services

```kotlin
import com.intellij.openapi.components.service
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.module.ModuleManager

// Project root manager
val rootManager = ProjectRootManager.getInstance(project)
println("SDK: ${rootManager.projectSdk?.name}")

// Module manager
val moduleManager = ModuleManager.getInstance(project)
moduleManager.modules.forEach { module ->
    println("Module: ${module.name}")
}

// Content roots
rootManager.contentRoots.forEach { root ->
    println("Content root: ${root.path}")
}

// Source roots
rootManager.contentSourceRoots.forEach { src ->
    println("Source root: ${src.path}")
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

## Search Scopes

```kotlin
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.openapi.module.ModuleManager

// Project scope (all project files)
val projectScope = GlobalSearchScope.projectScope(project)

// All scope (project + libraries)
val allScope = GlobalSearchScope.allScope(project)

// Module scope
val module = ModuleManager.getInstance(project).modules.firstOrNull()
if (module != null) {
    val moduleScope = GlobalSearchScope.moduleScope(module)
    println("Module scope: ${module.name}")
}

// File scope
val vf = // ... get virtual file
val fileScope = GlobalSearchScope.fileScope(project, vf)

// Multiple files scope
val filesScope = GlobalSearchScope.filesScope(project, listOf(vf1, vf2))
```

## Actions

### Find Action by ID
```kotlin
import com.intellij.openapi.actionSystem.ActionManager

val action = ActionManager.getInstance().getAction("GotoFile")
println("Action: ${action?.templatePresentation?.text}")
```

### List All Actions
```kotlin
import com.intellij.openapi.actionSystem.ActionManager

ActionManager.getInstance().getActionIdList("")
    .filter { it.contains("Goto", ignoreCase = true) }
    .take(20)
    .forEach { println(it) }
```

## Reflection for API Discovery

```kotlin
try {
    val clazz = Class.forName("com.intellij.openapi.project.Project")
    clazz.methods
        .filter { it.parameterCount == 0 }
        .take(20)
        .forEach { println("${it.name}(): ${it.returnType.simpleName}") }
} catch (e: ClassNotFoundException) {
    println("Class not found")
}
```

## Error Handling

Use `printException` for errors - it includes the stack trace in the output:

```kotlin
try {
    // risky operation
} catch (e: Exception) {
    printException("Operation failed", e)  // Recommended - includes stack trace in output
}
```

## Best Practices

1. **Use `smartReadAction { }` for PSI operations** - combines wait + read in one call
2. **Use built-in helpers** - `readAction`, `writeAction`, `projectScope()`, `allScope()` need no imports
3. **Use file helpers** - `findPsiFile()`, `findProjectFile()` simplify file access
4. **Imports are optional** - add top-level imports only when needed
5. **Prefer Kotlin coroutine APIs** - you're in a suspend context
6. **Use meaningful `task_id`** - groups related executions
7. **Report progress** - `progress("Step 1 of 5...")`
8. **Use `printException` for errors** - includes stack trace in output
9. **Keep trying** - IntelliJ API has a learning curve, persistence pays off
10. **Prefer IntelliJ APIs over file operations** - IDE has indexed everything

## Troubleshooting

### Check if Server is Running
The MCP server runs inside IntelliJ. To verify:
1. Open IntelliJ IDEA with the MCP Steroid plugin installed
2. Open any project
3. Check `.idea/mcp-steroid.md` in the project folder for the server URL
4. The server port is configurable via `mcp.steroid.server.port`; read `.idea/mcp-steroid.md` for the active URL

### Endpoints
- `/` - Returns this SKILL.md content
- `/skill.md` - Same as above
- `/mcp` - MCP protocol endpoint for tool calls
- `/.well-known/mcp.json` - MCP server discovery

### MCP Resources (Preferred)
Use MCP `resources/list` and `resources/read` instead of HTTP fetching when possible.

Recommended resources:
- Use `resources/list` to discover all available resources
- `mcp-steroid://skill/skill` - Full SKILL.md content as a resource
- `mcp-steroid://skill/debugger-skill` - Debugger workflow guide + stateful exec_code notes
- `mcp-steroid://ide/overview` - IDE usage overview and patterns
- `mcp-steroid://lsp/overview` - LSP-style workflows and examples
- `mcp-steroid://debugger/overview` - Debugger workflows and runnable examples

### Common Issues
- **"Project not found"** - Run `steroid_list_projects` first to get exact project names
- **No output from execute** - Make sure to call `println()` or `printJson()` to see results
- **Timeout** - Increase `timeout` parameter (default 60 seconds)
- **Script errors** - Check Kotlin syntax; imports are optional

## Related Resources

📊 Use `resources/list` to discover all available resources and their navigation paths

### Skill Guides
- [IntelliJ API Power User Guide](mcp-steroid://skill/skill) - This guide
- [Debugger Skill Guide](mcp-steroid://skill/debugger-skill) - Debug workflows and stateful execution
- [Test Runner Skill Guide](mcp-steroid://skill/test-skill) - Test execution patterns

### Example Resources
- [LSP Examples](mcp-steroid://lsp/overview) - LSP-like operations (navigation, code intelligence, refactoring)
- [IDE Examples](mcp-steroid://ide/overview) - IDE power operations (refactorings, inspections, generation)
- [Debugger Examples](mcp-steroid://debugger/overview) - Debugger workflows and API usage
- [Test Examples](mcp-steroid://test/overview) - Test execution and result inspection
- [VCS Examples](mcp-steroid://vcs/overview) - Version control operations (git blame, history)
- [Open Project Examples](mcp-steroid://open-project/overview) - Project opening workflows

### Specific Skill Resources
- [Debugger Guide](mcp-steroid://skill/debugger-skill) - Setting breakpoints and debugging
- [Test Runner Guide](mcp-steroid://skill/test-skill) - Running and inspecting tests

---

## Summary

The MCP Steroid server gives you **direct access to IntelliJ's runtime**:

- Query the project model
- Invoke any IntelliJ API
- Run refactorings
- Execute actions
- Inspect plugins
- Access PSI (parsed code)

**Key points:**
- The script body is a suspend function - use coroutine APIs directly
- Built-in helpers (`readAction`, `writeAction`, `projectScope()`, etc.) require no imports
- Use `smartReadAction { }` for most PSI operations
- Use `findPsiFile()` and `findProjectFile()` for easy file access

**This is like LSP, but more powerful.** IntelliJ APIs offer deeper code understanding and more features than standard LSP.

**Don't settle for file-level operations when you have IDE-level access.**

**Keep trying!** The API is vast - errors on first attempts are normal. Each attempt teaches you more.
