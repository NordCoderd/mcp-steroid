/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.testHelper

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files

class ProjectHomeDirectoryTest {
    @Test
    fun resolvesProjectHomeAndValidatesRequiredFiles() {
        val projectHome = ProjectHomeDirectory.requireProjectHomeDirectory()

        assertTrue(Files.isDirectory(projectHome), "Project home must be a directory")
        assertTrue(Files.isRegularFile(projectHome.resolve("build.gradle.kts")), "Project home must contain build.gradle.kts")
        assertTrue(Files.isRegularFile(projectHome.resolve("VERSION")), "Project home must contain VERSION")
    }
}
