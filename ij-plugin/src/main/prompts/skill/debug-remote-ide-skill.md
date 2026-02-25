How to Debug Another IDE Instance

Guide for AI agents on debugging IntelliJ-based IDEs (CLion, Rider, etc.) using IntelliJ IDEA as the debugger host with MCP Steroid.

# How to Debug Another IDE Instance (for AI Agents)

> **This guide was written entirely by AI agents** using MCP Steroid while working on the IntelliJ Platform codebase.
>
> **Note:** Specific plugin names, paths, and internal details use generic placeholders. Replace `YOUR_PLUGIN_ID`, `YourAction`, and example paths with your actual values.

**Guide for AI Agents:** Debugging IntelliJ-based IDEs (CLion, IDEA, Rider, etc.) using IntelliJ's debugger

---

## Overview

This guide explains how an AI agent can debug an IntelliJ-based IDE (like CLion) by:
1. Launching the IDE in debug mode from IntelliJ IDEA
2. Using MCP Steroid to interact with the debugged IDE
3. Taking screenshots to observe UI state
4. Using the debugger to inject code and inspect runtime state
5. Testing plugin functionality programmatically

**Use Case:** Validating a plugin in the target IDE while having full debugger control

---

## Architecture: Two IDEs Working Together

```
┌─────────────────────────────────────┐
│  IntelliJ IDEA (Debugger Host)      │
│                                     │
│  - intellij project open            │
│  - Run Configurations available     │
│  - Debugger UI active               │
│  - MCP Steroid connected            │
│  - Can execute Kotlin code          │
│  - Can set breakpoints              │
└────────────┬────────────────────────┘
             │ JDWP Debug Connection
             │ (port 60228, etc.)
             ▼
┌─────────────────────────────────────┐
│  Target IDE (Debugged)              │
│                                     │
│  - Running with -agentlib:jdwp      │
│  - Plugin under test loaded         │
│  - UI may be visible or headless    │
│  - Fully controllable via debugger  │
│  - State inspectable                │
└─────────────────────────────────────┘
```

**Key Insight:** IntelliJ IDEA becomes your "control center" for debugging any target IDE

---

## Step 1: Identify Available Run Configurations
```kotlin
import com.intellij.execution.RunManager

val runManager = RunManager.getInstance(project)
val allConfigs = runManager.allSettings

println("Available run configurations:")
allConfigs.forEach { config ->
    println("  - ${config.name}")
}
```

**What to Look For:**
- "CLion (dev build)"
- "IDEA (dev build)"
- "Rider (dev build)"
- Any configuration with `DevMainKt` as main class

---

## Step 2: Launch in Debug Mode Programmatically
```kotlin
import com.intellij.execution.RunManager
import com.intellij.execution.ProgramRunnerUtil
import com.intellij.execution.executors.DefaultDebugExecutor
import com.intellij.openapi.application.ApplicationManager

// Find the target IDE configuration
val runManager = RunManager.getInstance(project)
val targetConfig = runManager.allSettings.find {
    it.name == "TARGET_IDE (dev build)"  // Replace with your config name
}

if (targetConfig != null) {
    println("Found configuration: ${targetConfig.name}")

    // Get debug executor
    val debugExecutor = DefaultDebugExecutor.getDebugExecutorInstance()

    // Launch in debug mode (asynchronously on EDT)
    ApplicationManager.getApplication().invokeLater {
        println("Launching ${targetConfig.name} in debug mode...")
        ProgramRunnerUtil.executeConfiguration(targetConfig, debugExecutor)
        println("Debug session started")
    }
} else {
    println("Configuration not found")
}
```

**Important:** The IDE launch is asynchronous! Wait for startup before testing.

---

## Step 3: Set Breakpoints Programmatically

```text
import com.intellij.xdebugger.XDebuggerManager
import com.intellij.xdebugger.breakpoints.XLineBreakpointType
import com.intellij.openapi.vfs.VfsUtil
import java.nio.file.Paths

// Set a breakpoint in your plugin code
val file = VfsUtil.findFile(
    Paths.get(
        project.basePath!!,
        "plugins/your-plugin/src/com/example/YourAction.kt"  // Replace with your path
    ),
    true
)

if (file != null) {
    val breakpointManager = XDebuggerManager.getInstance(project).breakpointManager
    val lineBreakpointType = XLineBreakpointType.EXTENSION_POINT_NAME
        .extensionList
        .firstOrNull { it is XLineBreakpointType }

    if (lineBreakpointType != null) {
        breakpointManager.addLineBreakpoint(
            lineBreakpointType,
            file.url,
            35, // line number - adjust as needed
            null // condition
        )
        println("Breakpoint set")
    }
}
```

---

## Interacting with the Debugged IDE

### Execute Code in Target IDE's Context

When paused at a breakpoint, use "Evaluate Expression" (Alt+F8) to run code **inside the target IDE's JVM**:
```kotlin
// This code runs IN the target IDE's JVM, not IntelliJ's
import com.intellij.openapi.project.ProjectManager
import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.openapi.extensions.PluginId

// Get all open projects in target IDE
val projects = ProjectManager.getInstance().openProjects
println("Target IDE has ${projects.size} open projects:")
projects.forEach { p ->
    println("  - ${p.name} at ${p.basePath}")
}

// Check plugin status IN target IDE
val pluginId = PluginId.getId("YOUR_PLUGIN_ID")  // Replace with your plugin ID
val plugin = PluginManagerCore.getPlugin(pluginId)
println("Plugin:")
println("  Loaded: ${plugin != null}")
println("  Version: ${plugin?.version}")
```

### Trigger Actions Programmatically
```kotlin
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUiKind
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.ex.ActionUtil
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.project.ProjectManager

val targetProjects = ProjectManager.getInstance().openProjects
val targetProject = targetProjects.firstOrNull()

if (targetProject != null) {
    val actionManager = ActionManager.getInstance()
    val myAction = actionManager.getAction("YourPlugin.YourAction")  // Replace

    if (myAction != null) {
        val dataContext = SimpleDataContext.builder()
            .add(CommonDataKeys.PROJECT, targetProject)
            .build()

        val event = AnActionEvent.createEvent(dataContext, myAction.templatePresentation, "mcp", ActionUiKind.NONE, null)
        ActionUtil.performAction(myAction, event)
        println("Action invoked")
    }
}
```

---

## Observing the Debugged IDE

### Check Process Status

```bash
# Find target IDE process
ps aux | grep "DevMainKt" | grep -v grep

# Extract debug port
ps aux | grep "DevMainKt" | grep -o "address=[^,]*"
# Output: address=127.0.0.1:60228
```

### Monitor Logs

```bash
# Logs directory (adjust path for your IDE)
ls -la ~/Library/Logs/JetBrains/TARGET_IDE/

# Main log file
tail -f ~/Library/Logs/JetBrains/TARGET_IDE/idea.log

# Search for plugin activity
grep -i "your-plugin" idea.log | tail -20

# Look for errors
grep -i "error\|exception" idea.log | tail -10
```

### Check Window Visibility (macOS)

```bash
osascript -e 'tell application "System Events" to get count of windows of (first process whose name is "java")'
# Output: 0 = headless mode
```

---

## Common Patterns

### Pattern 1: Verify Plugin Loaded
```kotlin
import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.openapi.extensions.PluginId

val pluginId = PluginId.getId("YOUR_PLUGIN_ID")
val plugin = PluginManagerCore.getPlugin(pluginId)

if (plugin != null) {
    println("Plugin loaded: ${plugin.name} v${plugin.version}")
} else {
    println("Plugin not loaded")
}
```

### Pattern 2: List All Actions
```kotlin
import com.intellij.openapi.actionSystem.ActionManager

val actionManager = ActionManager.getInstance()
val allActionIds = actionManager.getActionIdList("")

val matchingActions = allActionIds.filter {
    it.contains("YourKeyword", ignoreCase = true)
}

println("Matching actions (${matchingActions.size}):")
matchingActions.forEach { actionId ->
    val action = actionManager.getAction(actionId)
    println("  - $actionId: ${action?.javaClass?.simpleName}")
}
```

### Pattern 3: State File Manipulation

```bash
# Create/modify plugin state to trigger reload
cat > /path/to/project/.idea/yourPlugin.xml << 'EOF'
<?xml version="1.0" encoding="UTF-8"?>
<project version="4">
  <component name="YourPluginService">
    <!-- Your plugin state here -->
  </component>
</project>
EOF
# Target IDE will detect file change and reload
```

---

## Troubleshooting

### Target IDE Won't Start

```bash
# Check port availability
lsof -i :5005  # or whatever debug port

# Kill conflicting process if needed
kill <PID>
```

### Breakpoints Don't Hit

1. Code not executed yet
2. Breakpoint in wrong file/line
3. Source mismatch (rebuild needed)

**Solution:** Add logging or use exception as breakpoint:
```kotlin
println("DEBUG: Reached this point")
// or
throw RuntimeException("Debug marker")
```

### No Visible Window

Target IDE may be running headless. Use programmatic approaches:
- Monitor logs
- Use state file manipulation
- Query via debugger code injection

---

## Best Practices for AI Agents

1. **Always use async launch** - `invokeLater` prevents blocking forever
2. **Wait for initialization** - Monitor logs for "Loaded bundled plugins"
3. **Check logs first** - Before UI automation, verify IDE started correctly
4. **Use multiple evidence sources** - Process status + debug connection + logs + state files
5. **Document everything** - PID, debug port, log excerpts, screenshots

### Evidence Checklist

```
Process running (ps aux)
Debug connection active (IntelliJ shows it)
Logs show plugin loaded
No errors in logs
State files updated
= High confidence plugin works
```

---

## Summary

**As an AI Agent, you can:**

1. Launch IDEs in debug mode programmatically
2. Use MCP Steroid to execute code in the host IDE
3. Inject code into target IDE via debugger "Evaluate Expression"
4. Set breakpoints and inspect runtime state
5. Take screenshots for visual verification
6. Monitor logs in real-time
7. Test plugins without UI automation
8. Modify behavior during debugging

**Workflow:**
```
Launch IDE -> Wait for startup -> Set breakpoints ->
Trigger functionality -> Inspect via debugger ->
Monitor logs -> Collect evidence -> Document results
```

**Remember:**
- Debugging is async - wait for readiness
- Headless mode is common - adapt your approach
- Logs are your best friend
- Multiple evidence sources = high confidence
- Document everything for reproducibility
