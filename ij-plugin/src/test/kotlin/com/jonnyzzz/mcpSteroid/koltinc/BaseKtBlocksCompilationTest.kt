/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.koltinc

import com.intellij.testFramework.common.timeoutRunBlocking
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.jonnyzzz.mcpSteroid.execution.CodeButcher
import com.jonnyzzz.mcpSteroid.prompts.PromptBase
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import kotlin.time.Duration.Companion.seconds

/**
 * Base class for generated KtBlocks compilation tests.
 *
 * Replaces [ScriptClassLoaderFactory] with
 * [FullIdeClasspathScriptClassLoaderFactory] so that all IDE plugin JARs
 * (including plugin-specific classes like JUnitConfiguration) are available
 * on the kotlinc compilation classpath.
 */
abstract class BaseKtBlocksCompilationTest : BasePlatformTestCase() {
    override fun runInDispatchThread(): Boolean = false

    protected fun compileKtBlock(block: PromptBase) {
        timeoutRunBlocking(120.seconds) {
            val content = block.readPrompt()
            val wrapped = CodeButcher().wrapToKotlinClass("MdKtBlock", content)
            val tempDir = Files.createTempDirectory("md-kt-block-compile")
            val sourceFile = tempDir.resolve("Script.kt")
            Files.writeString(sourceFile, wrapped.code, StandardCharsets.UTF_8)
            val outputJar = tempDir.resolve("out.jar")
            val classpath = FullIdeClasspathScriptClassLoaderFactory().ideClasspath()
            val cmd = KotlincCommandLineBuilder(outputJar)
                .withNoStdLib(true)
                .withExtraParameters(listOf("-Werror"))
                .addClasspathEntries(classpath)
                .addSource(sourceFile)
                .build()
            val result = kotlincProcessClient.kotlinc(cmd.args, tempDir)
            val output = (result.stdout + "\n" + result.stderr).trim()
            assertEquals(
                "Compilation failed or has warnings (-Werror):\n$output",
                0, result.exitCode,
            )
        }
    }
}
