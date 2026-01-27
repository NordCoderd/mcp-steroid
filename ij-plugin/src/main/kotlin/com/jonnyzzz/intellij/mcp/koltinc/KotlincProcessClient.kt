/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.intellij.mcp.koltinc

import com.intellij.execution.ExecutionException
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.ProcessOutput
import com.intellij.execution.util.ExecUtil
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.util.SystemInfoRt
import com.jonnyzzz.intellij.mcp.PluginDescriptorProvider
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

inline val kotlincProcessClient: KotlincProcessClient get() = service()

@Service(Service.Level.APP)
class KotlincProcessClient {
    private val log = thisLogger()

    fun kotlinc(vararg args: String, workingDir: Path? = null): ProcessOutput {
        return kotlinc(args.toList(), workingDir)
    }

    fun kotlinc(args: List<String>, workingDir: Path? = null): ProcessOutput {
        val executable = resolveExecutable()
        val commandLine = if (SystemInfoRt.isWindows) {
            GeneralCommandLine("cmd.exe", "/c", executable.path.toString())
        } else {
            GeneralCommandLine(executable.path.toString())
        }

        commandLine.withParameters(args)
            .withCharset(StandardCharsets.UTF_8)
            .withWorkDirectory((workingDir ?: executable.root).toFile())

        commandLine.withEnvironment("JAVA_HOME", System.getProperty("java.home"))
        commandLine.withEnvironment("KOTLIN_HOME", executable.root.toString())

        val output = runProcess(commandLine)
        if (output.stderr.isNotBlank()) {
            log.warn("kotlinc stderr: ${output.stderr.trim()}")
        }
        return output
    }

    fun isAvailable(): Boolean {
        return try {
            resolveExecutable()
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun runProcess(commandLine: GeneralCommandLine): ProcessOutput {
        val output = try {
            ExecUtil.execAndGetOutput(commandLine, KOTLINC_TIMEOUT_MS)
        } catch (e: ExecutionException) {
            throw IllegalStateException("Failed to start kotlinc process: ${e.message}", e)
        }

        if (output.isTimeout || output.isCancelled) {
            throw IllegalStateException("Kotlinc process did not finish before timeout")
        }
        if (output.exitCode != 0) {
            val stderr = output.stderr.trim()
            throw IllegalStateException("Kotlinc process failed with exit code ${output.exitCode}: $stderr")
        }
        return output
    }

    private fun resolveExecutable(): KotlincExecutable {
        val plugin = PluginDescriptorProvider.getInstance().descriptor
        val kotlincRoot = plugin.pluginPath.resolve("kotlinc")
        val bin = if (SystemInfoRt.isWindows) "bin/kotlinc.bat" else "bin/kotlinc"
        val executable = kotlincRoot.resolve(bin)

        if (!Files.exists(executable)) {
            throw IllegalStateException("Kotlinc executable not found: $executable")
        }
        if (!SystemInfoRt.isWindows && !Files.isExecutable(executable)) {
            executable.toFile().setExecutable(true)
        }
        return KotlincExecutable(executable, kotlincRoot)
    }

    private data class KotlincExecutable(val path: Path, val root: Path)

    companion object {
        private const val KOTLINC_TIMEOUT_MS = 120_000
    }
}
