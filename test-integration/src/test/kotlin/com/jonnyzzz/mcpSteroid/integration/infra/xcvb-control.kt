/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.integration.infra

import com.jonnyzzz.mcpSteroid.testHelper.assertExitCode
import com.jonnyzzz.mcpSteroid.testHelper.docker.ContainerDriver


class XcvbInputDriver(
    private val driver: ContainerDriver,
) {

    private fun xdotool(vararg args: String): String {
        return driver
            .runInContainer(
                listOf("xdotool") + args.toList(),
                timeoutSeconds = 10,
            )
            .assertExitCode(0, "[xcvb] xdotool ${args.joinToString(" ")} failed")
            .output.trim()
    }

    /** Copy text to the X11 clipboard. */
    fun clipboardCopy(text: String) {
        driver
            .runInContainer(
                listOf("bash", "-c", "echo -n ${shellEscape(text)} | xclip -selection clipboard"),
                timeoutSeconds = 5,
            ).assertExitCode(0, "[xcvb] clipboardCopy failed")
    }

    /** Read text from the X11 clipboard. */
    fun clipboardPaste(): String {
        return driver
            .runInContainer(
                listOf("xclip", "-selection", "clipboard", "-o"),
                timeoutSeconds = 5,
            )
            .assertExitCode(0, "[xcvb] clipboardPaste failed")
            .output.trim()
    }

    /** Move the mouse cursor to the given display coordinates. */
    fun mouseMove(x: Int, y: Int) {
        xdotool("mousemove", "--sync", x.toString(), y.toString())
    }

    /** Move the mouse to (x, y) and click the given button (1=left, 2=middle, 3=right). */
    fun mouseClick(x: Int, y: Int, button: Int = 1) {
        xdotool("mousemove", "--sync", x.toString(), y.toString())
        xdotool("click", button.toString())
    }

    /** Move the mouse to (x, y) and double-click. */
    fun mouseDoubleClick(x: Int, y: Int) {
        xdotool("mousemove", "--sync", x.toString(), y.toString())
        xdotool("click", "--repeat", "2", "1")
    }

    /**
     * Press a key or key combination.
     * Examples: `"Return"`, `"Tab"`, `"ctrl+s"`, `"alt+F4"`, `"shift+ctrl+p"`.
     */
    fun keyPress(key: String) {
        xdotool("key", key)
    }

    /** Type a text string character by character with a small inter-key delay. */
    fun typeText(text: String) {
        xdotool("type", "--delay", "50", "--", text)
    }

    /** Return the window ID of the currently active (focused) window. */
    fun getActiveWindowId(): String {
        return xdotool("getactivewindow").trim()
    }

    /** Activate (focus + raise) a window found by name pattern. */
    fun activateWindow(namePattern: String) {
        xdotool("search", "--name", namePattern, "windowactivate")
    }
}
