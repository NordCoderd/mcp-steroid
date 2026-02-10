/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.integration

import com.jonnyzzz.mcpSteroid.testHelper.CloseableStack
import com.jonnyzzz.mcpSteroid.testHelper.docker.ContainerDriver

/**
 * Provides a visible console window (xterm) on the right 1/3 of the screen
 * that displays test status messages in real-time.
 *
 * Uses `tail -f` to follow a log file; writing text appends to the file.
 * ANSI escape codes are supported natively by xterm.
 *
 * Immutable: created via [create] factory, cleanup registered in [CloseableStack].
 */
class ConsoleDriver private constructor(
    private val container: ContainerDriver,
    private val consoleFile: String,
) {

    fun writeLine(text: String) {
        val tempFile = "/tmp/console-line-${System.nanoTime()}"
        container.writeFileInContainer(tempFile, "$text\n")
        container.runInContainer(
            listOf("bash", "-c", "cat $tempFile >> $consoleFile && rm $tempFile"),
            timeoutSeconds = 5,
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
        writeLine("${GREEN}OK$RESET $text")
    }

    fun writeError(text: String) {
        writeLine("${RED}FAIL$RESET $text")
    }

    fun writeInfo(text: String) {
        writeLine("$BLUE>>>$RESET $text")
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
     * Returns a [PumpHandle] to stop the pump when the agent finishes.
     */
    fun startFilePump(
        filePath: String,
        prefix: String,
        prefixColor: String = CYAN,
    ): PumpHandle {
        // Shell script: tail -f the file, prefix each line, append to console.
        // Uses IFS= read -r to preserve leading whitespace and special chars.
        val script = buildString {
            append("touch $filePath && tail -f $filePath | while IFS= read -r line; do ")
            append("printf '${prefixColor}${prefix}${RESET} %s\\n' \"\$line\" >> $consoleFile; ")
            append("done")
        }

        val proc = container.runInContainerDetached(
            listOf("bash", "-c", script),
        )
        return PumpHandle(container, proc)
    }

    class PumpHandle(
        private val container: ContainerDriver,
        private val process: com.jonnyzzz.mcpSteroid.testHelper.docker.RunningContainerProcess,
    ) {
        fun stop() {
            try {
                // Give pump a moment to flush remaining lines
                Thread.sleep(300)
                if (process.isRunning()) {
                    process.kill()
                }
            } catch (_: Exception) {
                // pump cleanup is best-effort
            }
        }
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

        private val consoleCounter = java.util.concurrent.atomic.AtomicInteger(0)

        fun create(
            lifetime: CloseableStack,
            xcvbDriver: XcvbDriver,
            container: ContainerDriver,
            title: String = "Test Console",
        ): ConsoleDriver {
            val consoleFile = "/tmp/test-console-${consoleCounter.incrementAndGet()}"
            container.writeFileInContainer(consoleFile, "")

            val workArea = xcvbDriver.getWorkArea()
            val rect = WindowRect(
                x = workArea.x + workArea.width * 2 / 3 + 2,
                y = workArea.y + workArea.height / 6,
                width = workArea.width / 3 - 2,
                height = workArea.height * 4 / 6,
            )

            xcvbDriver.runInVisibleConsole(
                args = listOf("tail", "-f", consoleFile),
                title = title,
                windowRect = rect,
            )

            val driver = ConsoleDriver(container, consoleFile)
            driver.writeHeader(title)

            lifetime.registerCleanupAction {
                // xterm and tail will be cleaned up with container lifecycle
            }

            return driver
        }
    }
}
