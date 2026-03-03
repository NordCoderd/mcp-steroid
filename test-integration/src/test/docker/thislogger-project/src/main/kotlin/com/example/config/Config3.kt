/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.example.config

import com.example.util.thisLogger

class Config3 {
    private val logger = thisLogger()

    fun initialize() {
        logger.info("Config3 initializing")
    }

    fun shutdown() {
        logger.info("Config3 shutting down")
    }
}
