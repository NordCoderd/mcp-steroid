/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.example.repository

import com.example.util.thisLogger

class Repository13 {
    private val logger = thisLogger()

    fun findById(id: Long): String? {
        logger.debug("Repository13.findById: $id")
        return null
    }

    fun save(entity: String): Boolean {
        logger.info("Repository13.save: $entity")
        return true
    }

    fun delete(id: Long) {
        logger.warn("Repository13.delete: $id")
    }
}
