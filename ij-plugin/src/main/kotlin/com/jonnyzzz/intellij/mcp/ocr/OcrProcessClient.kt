/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.intellij.mcp.ocr

import com.intellij.execution.ExecutionException
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.ProcessOutput
import com.intellij.execution.util.ExecUtil
import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.extensions.PluginId
import com.intellij.openapi.util.SystemInfoRt
import kotlinx.serialization.json.Json
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

object OcrProcessClient {
    private val log = Logger.getInstance(OcrProcessClient::class.java)
    private val pluginId = PluginId.getId("com.jonnyzzz.intellij.mcp-steroid")
    private val json = Json { ignoreUnknownKeys = true }

    private const val OCR_TIMEOUT_MS = 120_000

    fun extractText(imagePath: Path, language: String = "eng", level: OcrLevel = OcrLevel.TEXT_LINE): OcrResult {
        require(Files.exists(imagePath)) { "OCR image does not exist: $imagePath" }
        val executable = resolveExecutable()
        val commandLine = if (SystemInfoRt.isWindows) {
            GeneralCommandLine("cmd.exe", "/c", executable.path.toString())
        } else {
            GeneralCommandLine(executable.path.toString())
        }
        commandLine.withParameters("--image", imagePath.toString(), "--lang", language, "--level", level.cliToken())
            .withCharset(StandardCharsets.UTF_8)
            .withWorkDirectory(executable.root.toFile())

        commandLine.withEnvironment("JAVA_HOME", System.getProperty("java.home"))

        val output = runProcess(commandLine)
        val stdout = output.stdout.trim()
        if (stdout.isBlank()) {
            throw IllegalStateException("OCR returned empty output. stderr=${output.stderr.trim()}")
        }
        return json.decodeFromString(OcrResult.serializer(), stdout)
    }

    private fun runProcess(commandLine: GeneralCommandLine): ProcessOutput {
        val output = try {
            ExecUtil.execAndGetOutput(commandLine, OCR_TIMEOUT_MS)
        } catch (e: ExecutionException) {
            throw IllegalStateException("Failed to start OCR process: ${e.message}", e)
        }

        if (output.isTimeout || output.isCancelled) {
            throw IllegalStateException("OCR process did not finish before timeout")
        }
        if (output.exitCode != 0) {
            val stderr = output.stderr.trim()
            throw IllegalStateException("OCR process failed with exit code ${output.exitCode}: $stderr")
        }
        if (output.stderr.isNotBlank()) {
            log.warn("OCR process stderr: ${output.stderr.trim()}")
        }
        return output
    }

    private fun resolveExecutable(): OcrExecutable {
        val plugin = PluginManagerCore.getPlugin(pluginId)
            ?: throw IllegalStateException("OCR process not available: plugin not found")
        val ocrRoot = plugin.pluginPath.resolve("ocr-tesseract")
        val bin = if (SystemInfoRt.isWindows) "bin/ocr-tesseract.bat" else "bin/ocr-tesseract"
        val executable = ocrRoot.resolve(bin)

        if (!Files.exists(executable)) {
            throw IllegalStateException("OCR executable not found: $executable")
        }
        if (!SystemInfoRt.isWindows && !Files.isExecutable(executable)) {
            executable.toFile().setExecutable(true)
        }
        return OcrExecutable(executable, ocrRoot)
    }

    private fun OcrLevel.cliToken(): String = name.lowercase()

    private data class OcrExecutable(val path: Path, val root: Path)
}
