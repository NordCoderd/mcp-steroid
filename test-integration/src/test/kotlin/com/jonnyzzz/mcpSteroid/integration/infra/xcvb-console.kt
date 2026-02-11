/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.integration.infra

import com.jonnyzzz.mcpSteroid.testHelper.CloseableStack
import com.jonnyzzz.mcpSteroid.testHelper.docker.ContainerDriver
import com.jonnyzzz.mcpSteroid.testHelper.docker.RunningContainerProcess
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

class PumpHandle(
    private val process: RunningContainerProcess,
) {
    fun stop() {
        try {
            // Give pump a moment to flush remaining lines
            Thread.sleep(300)
            process.kill()
        } catch (_: Exception) {
            // pump cleanup is best-effort
        }
    }
}

/**
 * Provides a visible console window (xterm) on the right 1/3 of the screen
 * that displays test status messages in real-time.
 *
 * Uses `tail -f` to follow a log file; writing text appends to the file.
 * ANSI escape codes are supported natively by xterm.
 *
 * Immutable: created via [create] factory, cleanup registered in [CloseableStack].
 */
class ConsoleDriver(
    private val container: ContainerDriver,
    private val consoleFile: String,
) {

    fun writeLine(text: String) {
        // Single docker exec call: heredoc append with quoted delimiter to prevent expansion
        container.runInContainer(
            listOf("bash", "-c", "cat >> $consoleFile << 'CONSOLE_LINE_END'\n$text\nCONSOLE_LINE_END"),
            timeoutSeconds = 5,
            quietly = true,
        )
    }

    // -- ANSI formatting helpers --
    fun writeHeader(text: String) {
        writeLine("")
        writeLine("$BOLD$CYAN${"═".repeat(40)}$RESET")
        writeLine("$BOLD$CYAN  $text$RESET")
        writeLine("$BOLD$CYAN${"═".repeat(40)}$RESET")
        writeLine("")
    }

    fun writeStep(step: Int, text: String) {
        writeLine("$BOLD$YELLOW[$step]$RESET $text")
    }

    fun writeSuccess(text: String) {
        writeLine(" ${GREEN}OK$RESET $text")
    }

    fun writeError(text: String) {
        writeLine("${RED}FAIL$RESET $text")
    }

    fun writeInfo(text: String) {
        println(text)
        writeLine("$BLUE>>>$RESET $text")
    }

    fun <T> writeProgressBlock(test: String, action: () -> T) : T {
        writeInfo(test)
        return action()
    }

    fun writePrompt(label: String, prompt: String) {
        writeLine("")
        writeLine("$BOLD$BRIGHT_WHITE--- $label ---$RESET")
        prompt.lineSequence().forEach { line ->
            writeLine("$BRIGHT_WHITE$line$RESET")
        }
        writeLine("$BOLD$BRIGHT_WHITE--- end ---$RESET")
        writeLine("")
    }

    /**
     * Start a background process in the container that pumps lines from [filePath]
     * to the console file, prefixing each line with a colored [prefix].
     *
     * Uses `tail -F` (follows by name, handles truncation/replacement) and `awk`
     * with explicit `fflush()` for immediate, unbuffered line-by-line display.
     * The pump script is written to a file in the container to avoid shell escaping
     * issues with variable expansion.
     *
     * When [filterScript] is provided, its path is inserted into the pipeline
     * between `tail` and `awk` to transform raw output (e.g. NDJSON) into
     * human-readable text before it reaches the console.
     *
     * Returns a [PumpHandle] to stop the pump when the agent finishes.
     */
    fun startFilePump(
        filePath: String,
        prefix: String,
        prefixColor: String = CYAN,
        filterScript: String? = null,
    ): PumpHandle {
        val scriptPath = "/tmp/pump-${System.nanoTime()}.sh"
        val awkPrefix = prefix.replace("\\", "\\\\").replace("\"", "\\\"")
        // Map ANSI color constant to SGR code number for awk printf
        val colorCode = when (prefixColor) {
            RED -> "31"
            GREEN -> "32"
            YELLOW -> "33"
            BLUE -> "34"
            CYAN -> "36"
            else -> "36"
        }
        val filterStage = if (filterScript != null) " | python3 -u $filterScript" else ""
        val script = buildString {
            appendLine("#!/bin/bash")
            appendLine("touch $filePath")
            // tail -F follows by name (handles truncation/replacement)
            // -s 0.1 polls every 100ms instead of default 1s for smoother updates
            // optional filter transforms raw output (e.g. NDJSON) to readable text
            // python3 -u ensures unbuffered output from the filter
            // awk flushes both stdout and the console file after each line
            appendLine("tail -F -s 0.1 $filePath 2>/dev/null$filterStage | awk '{printf \"\\033[${colorCode}m${awkPrefix}\\033[0m %s\\n\", \$0 >> \"$consoleFile\"; fflush(\"$consoleFile\")}'")
        }
        container.writeFileInContainer(scriptPath, script, executable = true)

        val proc = container.runInContainerDetached(
            listOf("bash", scriptPath),
        )
        return PumpHandle(proc)
    }

    companion object {
        const val RESET = "\u001b[0m"
        const val BOLD = "\u001b[1m"
        const val RED = "\u001b[31m"
        const val GREEN = "\u001b[32m"
        const val YELLOW = "\u001b[33m"
        const val BLUE = "\u001b[34m"
        const val CYAN = "\u001b[36m"
        const val BRIGHT_WHITE = "\u001b[97m"

    }
}


class XcvbConsoleDriver(
    private val lifetime: CloseableStack,
    private val driver: ContainerDriver,
    private val windowDriver: XcvbWindowDriver,
) {
    private val consoleCounter = AtomicInteger(0)

    fun createConsoleDriver(
        container: ContainerDriver,
        title: String,
        layoutRect: WindowRect,
    ): ConsoleDriver {
        val consoleWorkDir = "/tmp"
        val consoleFile = "$consoleWorkDir/test-console-${consoleCounter.incrementAndGet()}"
        container.writeFileInContainer(consoleFile, "Thinking...")

        val xtermArgs = mutableListOf(
            "xterm",
            "-u8",
            "-title", title,
            "-geometry", "80x30+${layoutRect.x}+${layoutRect.y}",
            "-fa", "JetBrains Mono:style=Regular",
            "-fs", "16",
            "-bg", "black",
            "-fg", "white",
            "-e",

            "tail", "-f", "-s", "0.1", consoleFile
        )

        println("[xcvb] Starting visible console '$title'")

        val consoleProcess = driver.runInContainerDetached(
            xtermArgs,
            workingDir = consoleWorkDir,
        )

        lifetime.registerCleanupAction {
            consoleProcess.kill()
        }

        val consoleWindowId = waitForValue(5_000, "Wait for console Window") {
            windowDriver.listWindows().firstOrNull { it.pid == consoleProcess.pid }
        }

        windowDriver.updateLayout(consoleWindowId, layoutRect)
        val driver = ConsoleDriver(container, consoleFile)
        driver.writeHeader(title)
        return driver
    }
}
