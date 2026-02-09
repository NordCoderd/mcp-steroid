/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.testHelper

import java.nio.file.Files
import java.nio.file.Path

object ProjectHomeDirectory {
    const val SYSTEM_PROPERTY_NAME = "mcp.steroid.test.projectHome"

    fun requireProjectHomeDirectory(): Path {
        val configuredPath = System.getProperty(SYSTEM_PROPERTY_NAME)
            ?: error("System property '$SYSTEM_PROPERTY_NAME' is not set. Run tests via Gradle.")
        val projectHome = Path.of(configuredPath).toAbsolutePath().normalize()
        check(Files.isDirectory(projectHome)) {
            "Configured project home directory does not exist: $projectHome (from '$SYSTEM_PROPERTY_NAME')."
        }
        requireRegularFile(projectHome, "build.gradle.kts")
        requireRegularFile(projectHome, "VERSION")
        return projectHome
    }

    private fun requireRegularFile(projectHome: Path, relativePath: String) {
        val file = projectHome.resolve(relativePath)
        check(Files.isRegularFile(file)) {
            "Required project file is missing: $file (from '$SYSTEM_PROPERTY_NAME')."
        }
    }
}
