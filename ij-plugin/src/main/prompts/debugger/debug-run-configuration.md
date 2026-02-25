Debug Run Configuration

Start an existing run configuration in Debug mode using

```kotlin
import com.intellij.execution.ProgramRunnerUtil
import com.intellij.execution.RunManager
import com.intellij.execution.executors.DefaultDebugExecutor

val configurationName = "mcp-steroid [test]"  // TODO: Set your run configuration name
val runManager = RunManager.getInstance(project)
val settings = runManager.allSettings.firstOrNull { it.name == configurationName }
    ?: error("Run configuration not found: $configurationName")

val executor = DefaultDebugExecutor.getDebugExecutorInstance()
// IntelliJ 253 package: com.intellij.execution.ProgramRunnerUtil
withContext(Dispatchers.EDT) {
    ProgramRunnerUtil.executeConfiguration(settings, executor)
}

println("Started debug configuration:", settings.name)
```

# See also

Related IDE operations:
- [Run Configuration](mcp-steroid://ide/run-configuration) - List and execute run configs

Related test operations:
- [Run Tests](mcp-steroid://test/run-tests) - Execute test configuration
- [Wait for Completion](mcp-steroid://test/wait-for-completion) - Poll test status

Overview resources:
- [Debugger Skill Guide](mcp-steroid://skill/debugger-skill) - Essential debugger knowledge
