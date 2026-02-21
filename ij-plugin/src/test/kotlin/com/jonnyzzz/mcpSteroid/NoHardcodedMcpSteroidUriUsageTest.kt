/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.jonnyzzz.mcpSteroid.testHelper.ProjectHomeDirectory
import java.nio.file.Files
import java.nio.file.Path
import java.util.stream.Collectors

class NoHardcodedMcpSteroidUriUsageTest : BasePlatformTestCase() {
    fun testNoHardcodedMcpSteroidUriInKotlinSources() {
        val projectHome = ProjectHomeDirectory.requireProjectHomeDirectory()
        val forbiddenLiteral = "mcp-steroid://"
        // Scope: ij-plugin only — test-integration and test-helper are intentionally excluded.
        val sourceRoots = listOf(
            "ij-plugin/src/main/kotlin",
            "ij-plugin/src/test/kotlin",
        ).map(projectHome::resolve)

        // Exclude this file itself so it can use the literal without self-evasion tricks.
        val selfPath = projectHome
            .resolve("ij-plugin/src/test/kotlin/com/jonnyzzz/mcpSteroid/NoHardcodedMcpSteroidUriUsageTest.kt")
            .normalize()

        val matches = mutableListOf<String>()
        for (root in sourceRoots) {
            if (!Files.isDirectory(root)) continue
            for (file in collectKotlinFiles(root)) {
                if (file.normalize() == selfPath) continue
                val lines = Files.readAllLines(file)
                lines.forEachIndexed { index, line ->
                    if (line.contains(forbiddenLiteral)) {
                        matches.add("${projectHome.relativize(file)}:${index + 1}")
                    }
                }
            }
        }

        assertTrue(
            "Hardcoded MCP resource URI literals found in Kotlin code:\n${matches.joinToString("\n")}",
            matches.isEmpty()
        )
    }

    private fun collectKotlinFiles(root: Path): List<Path> {
        return Files.walk(root).use { paths ->
            paths
                .filter { it.isKotlinFile() }
                .collect(Collectors.toList())
        }
    }

    private fun Path.isKotlinFile(): Boolean {
        if (!Files.isRegularFile(this)) return false
        val fileName = fileName.toString()
        return fileName.endsWith(".kt") || fileName.endsWith(".kts")
    }
}
