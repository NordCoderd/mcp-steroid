/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.demo

import com.intellij.openapi.util.registry.Registry

/**
 * Accessors for Demo Mode registry keys.
 * 
 * Registry keys:
 * - mcp.steroid.demo.enabled: Enable Demo Mode overlay (default: false)
 * - mcp.steroid.demo.minDisplayTime: Minimum display time in ms (default: 3000)
 * - mcp.steroid.demo.maxLines: Maximum log lines to show (default: 15)
 * - mcp.steroid.demo.opacity: Background opacity 0-100 (default: 85)
 * - mcp.steroid.demo.focusFrame: Bring project frame to front (default: true)
 */
object DemoModeSettings {

    /**
     * Whether Demo Mode is enabled.
     */
    val isEnabled: Boolean
        get() = Registry.`is`("mcp.steroid.demo.enabled", false)

    /**
     * Minimum time to display the overlay in milliseconds.
     * Even if the command finishes faster, the overlay stays visible for this duration.
     */
    val minDisplayTimeMs: Int
        get() = Registry.intValue("mcp.steroid.demo.minDisplayTime", 3000)

    /**
     * Maximum number of log lines to show in the console area.
     */
    val maxLines: Int
        get() = Registry.intValue("mcp.steroid.demo.maxLines", 15)

    /**
     * Background opacity as a float (0.0 to 1.0).
     * Registry stores as integer 0-100.
     */
    @Suppress("unused") // Available for future customization
    val opacity: Float
        get() = Registry.intValue("mcp.steroid.demo.opacity", 85) / 100f

    /**
     * Whether to bring the project frame to front when showing the overlay.
     */
    val focusFrame: Boolean
        get() = Registry.`is`("mcp.steroid.demo.focusFrame", true)
}
