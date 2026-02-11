/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.integration

/**
 * Defines the display resolution and calculates target window rects
 * for the IDE and console layout.
 *
 * The layout manager is the single source of truth for display dimensions
 * and window zone positioning.
 */
interface LayoutManager {
    /** Display width in pixels. Used by XcvbDriver to configure the virtual display. */
    val displayWidth: Int

    /** Display height in pixels. Used by XcvbDriver to configure the virtual display. */
    val displayHeight: Int

    /** Target rect for IDE project window(s) — left-aligned, occupies 2/3 of the screen. */
    fun layoutIdeWindows(): WindowRect

    /** Target rect for the status/console window — right-aligned, occupies 1/3 of the screen. */
    fun layoutStatusConsoleWindow(): WindowRect
}

/**
 * Default layout: IDE takes left 2/3 minus 2px gap, console takes right 1/3 minus 2px gap.
 * This leaves a 4px visual separator between the two zones.
 *
 * ```
 * ┌──────────────────────────────┬──┬───────────────┐
 * │  IDE (2/3 width - 2px)       │4 │ Console       │
 * │  aligned left                │px│ (1/3 w - 2px) │
 * │                              │  │ aligned right  │
 * └──────────────────────────────┴──┴───────────────┘
 * ```
 *
 * Create the layout manager first with the desired resolution, then set [xcvb]
 * after the display driver is initialized so that [layoutIdeWindows] and
 * [layoutStatusConsoleWindow] can query the actual work area.
 */
class DefaultLayoutManager(
    override val displayWidth: Int = 3840,
    override val displayHeight: Int = 2160,
) : LayoutManager {
    /** Set after XcvbDriver is created. Required before calling layout methods. */
    lateinit var xcvb: XcvbDriver

    override fun layoutIdeWindows(): WindowRect {
        val workArea = xcvb.getWorkArea()
        return WindowRect(
            x = workArea.x,
            y = workArea.y,
            width = workArea.width * 2 / 3 - 2,
            height = workArea.height,
        )
    }

    override fun layoutStatusConsoleWindow(): WindowRect {
        val workArea = xcvb.getWorkArea()
        val consoleWidth = workArea.width / 3 - 2
        return WindowRect(
            x = workArea.x + workArea.width - consoleWidth,
            y = workArea.y,
            width = consoleWidth,
            height = workArea.height,
        )
    }
}
