/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.integration.infra

import com.jonnyzzz.mcpSteroid.testHelper.CloseableStack
import com.jonnyzzz.mcpSteroid.testHelper.assertExitCode
import com.jonnyzzz.mcpSteroid.testHelper.docker.ContainerDriver

data class WindowRect(
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
)

data class WindowInfo(
    val id: String,
    val title: String,
    val rect: WindowRect,
    val pid: Long,
)

class XcvbWindowDriver(
    private val lifetime: CloseableStack,
    private val driver: ContainerDriver,
    private val wholeScreen: WindowRect,
) {
    fun startWindowManager() {
        // Override the Debian fluxbox style wallpaper (which references an
        // image from desktop-base that isn't installed) with our own.
        driver.writeFileInContainer(
            "/home/agent/.fluxbox/overlay",
            "background: fullscreen\nbackground.pixmap: /usr/share/images/mcp-steroid-wallpaper.jpg\n",
        )

        println("[xcvb] Starting fluxbox...")
        val proc = driver.runInContainerDetached(
            listOf("fluxbox"),
        )

        lifetime.registerCleanupAction {
            proc.kill()
        }
    }

    fun listWindows(): List<WindowInfo> {
        // Run a shell script to list all windows with their geometry and title efficiently
        // Output format: ID|X|Y|WIDTH|HEIGHT|PID|TITLE
        val d = '$'
        val script = """
            for id in ${d}(xdotool search --name "" 2>/dev/null); do
              unset X Y WIDTH HEIGHT PID
              name=${d}(xdotool getwindowname "${d}id" 2>/dev/null)
              eval ${d}(xdotool getwindowgeometry --shell "${d}id" 2>/dev/null)
              pid=${d}(xdotool getwindowpid "${d}id" 2>/dev/null)
              if [ -n "${d}X" ] && [ -n "${d}Y" ] && [ -n "${d}WIDTH" ] && [ -n "${d}HEIGHT" ]; then
                echo "${d}id|${d}X|${d}Y|${d}WIDTH|${d}HEIGHT|${d}pid|${d}name"
              fi
            done
        """.trimIndent()

        val result = driver.runInContainer(
            listOf("bash", "-c", script),
            timeoutSeconds = 5,
        )
        if (result.exitCode != 0) return emptyList()

        return result.output.lineSequence()
            .filter { it.isNotBlank() }
            .mapNotNull { line ->
                val parts = line.split('|', limit = 7)
                if (parts.size < 7) return@mapNotNull null
                val id = parts[0]
                val x = parts[1].toIntOrNull() ?: return@mapNotNull null
                val y = parts[2].toIntOrNull() ?: return@mapNotNull null
                val width = parts[3].toIntOrNull() ?: return@mapNotNull null
                val height = parts[4].toIntOrNull() ?: return@mapNotNull null
                val pid = parts[5]
                val title = parts[6]

                WindowInfo(id, title, WindowRect(x, y, width, height), pid.toLong())
            }
            .toList()
    }

    fun getDisplayArea(): WindowRect = wholeScreen

    /**
     * Query the usable screen area from the window manager.
     * Uses `xprop` to read the `_NET_WORKAREA` property from the X root window,
     * which excludes taskbars, panels, and other reserved areas.
     *
     * Falls back to the full display size if the property is not available.
     */
    fun getWorkArea(): WindowRect {
        val result = driver.runInContainer(
            listOf("xprop", "-root", "_NET_WORKAREA"),
            timeoutSeconds = 5,
        ).assertExitCode(0)

        // Output format: _NET_WORKAREA(CARDINAL) = 0, 0, 3840, 2140
        val match = Regex("""(\d+),\s*(\d+),\s*(\d+),\s*(\d+)""")
            .find(result.output)
            ?: error("Failed to find the work area")

        val (x, y, w, h) = match.destructured
        return WindowRect(x.toInt(), y.toInt(), w.toInt(), h.toInt())
    }


    fun updateLayout(window: WindowInfo, rect: WindowRect) {
        val windowId = window.id

        driver.runInContainer(
            listOf("xdotool", "windowsize", "--sync", windowId, rect.width.toString(), rect.height.toString()),
            timeoutSeconds = 5,
        ).assertExitCode(0)

        driver.runInContainer(
            listOf("xdotool", "windowmove", "--sync", windowId, rect.x.toString(), rect.y.toString()),
            timeoutSeconds = 5,
        ).assertExitCode(0)

        driver.runInContainer(
            listOf("xdotool", "windowraise", windowId),
            timeoutSeconds = 5,
        ).assertExitCode(0)
    }
}
