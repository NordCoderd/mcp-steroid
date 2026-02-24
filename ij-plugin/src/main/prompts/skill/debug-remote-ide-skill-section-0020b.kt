/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
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
