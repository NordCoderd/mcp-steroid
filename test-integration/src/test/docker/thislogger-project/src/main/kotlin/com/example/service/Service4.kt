/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.example.service

import com.example.util.thisLogger

class Service4 {
    private val logger = thisLogger()

    fun process(input: String): String {
        logger.info("Service4.process called with: $input")
        return input.trim()
    }

    fun validate(value: Int): Boolean {
        logger.debug("Service4.validate: $value")
        return value > 0
    }
}
