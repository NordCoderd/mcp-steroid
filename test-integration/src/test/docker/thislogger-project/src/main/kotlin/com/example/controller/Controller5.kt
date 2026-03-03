/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.example.controller

import com.example.util.thisLogger

class Controller5 {
    private val logger = thisLogger()

    fun handleRequest(path: String): String {
        logger.info("Controller5.handleRequest: $path")
        return "OK"
    }

    fun handleError(e: Exception) {
        logger.error("Controller5.handleError: ${e.message}")
    }
}
