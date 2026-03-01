/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.prompts

import com.jonnyzzz.mcpSteroid.koltinc.CodeWrapperForCompilation
import com.jonnyzzz.mcpSteroid.koltinc.KotlincCommandLineBuilder
import com.jonnyzzz.mcpSteroid.koltinc.toArgFile
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.isRegularFile
import kotlin.io.path.name
import kotlin.io.path.walk

/**
 * Base class for generated KtBlock compilation tests (JUnit 5).
 *
 * Compiles Kotlin code blocks from prompt articles against the full IDE classpath
 * using an external kotlinc process. The IDE home, kotlinc home, and ij-plugin source
 * directory are resolved from system properties set by the Gradle build.
 *
 * The wrapper code references `McpScriptContext` and `McpScriptBuilder` (matching
 * the real `CodeButcher` output). Their source files from ij-plugin are compiled
 * together with the wrapped test code.
 *
 * System properties:
 * - `mcp.steroid.ide.home` — path to the unpacked IDE distribution
 * - `mcp.steroid.kotlinc.home` — path to the unpacked kotlinc distribution (parent of `kotlinc/`)
 * - `mcp.steroid.ij.sources` — path to ij-plugin/src/main/kotlin (for McpScriptContext/McpScriptBuilder sources)
 */
abstract class KtBlockCompilationTestBase {

    protected fun compileKtBlock(block: PromptBase) {
        val content = block.readPrompt()
        val wrapped = CodeWrapperForCompilation.wrap("MdKtBlock", content).code
        val tempDir = Files.createTempDirectory("md-kt-block-compile")
        try {
            val sourceFile = tempDir.resolve("Script.kt")
            Files.writeString(sourceFile, wrapped, StandardCharsets.UTF_8)
            val outputJar = tempDir.resolve("out.jar")
            val classpath = ideClasspath()
            val builder = KotlincCommandLineBuilder(outputJar)
                .withNoStdLib(true)
                .withExtraParameters(listOf("-Werror"))
                .addClasspathEntries(classpath)
                .addSource(sourceFile)

            // Add McpScriptContext and McpScriptBuilder source files from ij-plugin
            // so they compile together with the wrapped code
            for (sourceExtra in ijPluginSourceFiles()) {
                builder.addSource(sourceExtra)
            }

            val cmd = builder.build()
            val argFile = tempDir.resolve("kotlinc.args")
            val argCmd = cmd.toArgFile(argFile)

            val kotlincBin = resolveKotlincBin()
            val process = ProcessBuilder(kotlincBin.absolutePath, *argCmd.args.toTypedArray())
                .directory(tempDir.toFile())
                .redirectErrorStream(true)
                .start()

            val output = process.inputStream.bufferedReader().readText()
            val exitCode = process.waitFor()

            // this test depends on the classes on the disk
            // parallel execution of tests can break it
            // re-run usually is enough to fix that
            assert(exitCode == 0) {
                "Compilation failed or has warnings (-Werror):\n$output"
            }
        } finally {
            tempDir.toFile().deleteRecursively()
        }
    }

    companion object {
        private val ideClasspathCache: List<Path> by lazy {
            val home = System.getProperty("mcp.steroid.ide.home")
                ?: error("Missing system property 'mcp.steroid.ide.home'")
            Path.of(home)
                .walk()
                .filter { it.isRegularFile() && it.name.endsWith(".jar") }
                .toList()
        }

        private val ijPluginSourceFilesCache: List<Path> by lazy {
            val ijSourcesDir = System.getProperty("mcp.steroid.ij.sources")
                ?: error("Missing system property 'mcp.steroid.ij.sources'")
            val executionDir = Path.of(ijSourcesDir, "com", "jonnyzzz", "mcpSteroid", "execution")
            listOf("McpScriptContext.kt", "McpScriptBuilder.kt").map { fileName ->
                val file = executionDir.resolve(fileName)
                require(file.isRegularFile()) { "ij-plugin source file not found: $file" }
                file
            }
        }

        private fun ideClasspath(): List<Path> = ideClasspathCache

        private fun ijPluginSourceFiles(): List<Path> = ijPluginSourceFilesCache

        private fun resolveKotlincBin(): File {
            val kotlincHome = System.getProperty("mcp.steroid.kotlinc.home")
                ?: error("Missing system property 'mcp.steroid.kotlinc.home'")
            val kotlincDir = File(kotlincHome, "kotlinc")
            val bin = File(kotlincDir, "bin/kotlinc")
            require(bin.isFile) { "kotlinc binary not found at: $bin" }
            return bin
        }
    }
}
