/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.testHelper

interface CloseableStack {
    fun registerCleanupAction(cleanupAction: () -> Unit)
}

class CloseableStackHost : CloseableStack {
    private val cleanupActions = mutableListOf<() -> Unit>()

    override fun registerCleanupAction(cleanupAction: () -> Unit) {
        cleanupActions += cleanupAction
    }

    fun closeAllStacks() {
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
        if (errors.isNotEmpty()) {
            throw Error("Error during cleanup").apply {
                errors.forEach {
                    addSuppressed(it)
                }
            }
        }
    }
}
