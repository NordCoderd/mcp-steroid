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
        val forbiddenLiteral = "mcp-steroid" + "://"
        val sourceRoots = listOf(
            "src/main/kotlin",
            "src/test/kotlin",
            "test-helper/src/main/kotlin",
            "test-helper/src/test/kotlin",
            "test-integration/src/test/kotlin",
        ).map(projectHome::resolve)

        val matches = mutableListOf<String>()
        for (root in sourceRoots) {
            if (!Files.isDirectory(root)) continue
            for (file in collectKotlinFiles(root)) {
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
