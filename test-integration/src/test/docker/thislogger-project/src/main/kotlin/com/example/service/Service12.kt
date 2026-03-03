/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.example.service

import com.example.util.thisLogger

class Service12 {
    private val logger = thisLogger()

    fun process(input: String): String {
        logger.info("Service12.process called with: $input")
        return input.trim()
    }

    fun validate(value: Int): Boolean {
        logger.debug("Service12.validate: $value")
        return value > 0
    }
}
