/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.example.controller

import com.example.util.thisLogger

class Controller7 {
    private val logger = thisLogger()

    fun handleRequest(path: String): String {
        logger.info("Controller7.handleRequest: $path")
        return "OK"
    }

    fun handleError(e: Exception) {
        logger.error("Controller7.handleError: ${e.message}")
    }
}
