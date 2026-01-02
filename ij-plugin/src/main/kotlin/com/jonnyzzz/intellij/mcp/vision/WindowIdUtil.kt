/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.intellij.mcp.vision

import java.awt.Component
import java.awt.Window

object WindowIdUtil {
    fun compute(window: Window?, component: Component): String {
        return if (window != null) {
            "w-" + Integer.toHexString(System.identityHashCode(window))
        } else {
            "c-" + Integer.toHexString(System.identityHashCode(component))
        }
    }
}
