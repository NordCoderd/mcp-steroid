/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.testHelper

class CloseableStack : AutoCloseable {
    private val cleanupActions = mutableListOf<() -> Unit>()

    fun registerCleanupAction(cleanupAction: () -> Unit) {
        cleanupActions += cleanupAction
    }

    override fun close() {
        val errors = mutableListOf<Throwable>()
        cleanupActions.reversed().forEach {
            try {
                it()
            } catch (t: Throwable) {
                println("Error during cleanup: ${t.message}")
                println(t.stackTraceToString())
                errors.add(t)
            }
        }
        throw Error("Error during cleanup").apply {
            errors.forEach {
                addSuppressed(it)
            }
        }
    }
}
