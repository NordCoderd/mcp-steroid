/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
import com.intellij.execution.RunManager

val runManager = RunManager.getInstance(project)
val allConfigs = runManager.allSettings

println("Available run configurations:")
allConfigs.forEach { config ->
    println("  - ${config.name}")
}
