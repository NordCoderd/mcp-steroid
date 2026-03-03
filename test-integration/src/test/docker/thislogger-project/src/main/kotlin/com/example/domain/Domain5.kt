/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.example.domain

import com.example.util.thisLogger

class Domain5(val id: Long, val name: String) {
    private val logger = thisLogger()

    fun validate(): Boolean {
        logger.debug("Domain5.validate: id=$id, name=$name")
        return id > 0 && name.isNotBlank()
    }
}
