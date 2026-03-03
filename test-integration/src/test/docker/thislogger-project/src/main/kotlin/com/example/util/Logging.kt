/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.example.util

/**
 * Stub logger interface used in this project.
 * In real projects this would be backed by SLF4J / kotlin-logging.
 */
interface Logger {
    fun debug(msg: String)
    fun info(msg: String)
    fun warn(msg: String)
    fun error(msg: String)
}

/**
 * Returns a logger for the calling class.
 * Usage: private val logger = thisLogger()
 *
 * This mimics the IntelliJ Platform `thisLogger()` extension function
 * (com.intellij.openapi.diagnostic.thisLogger) which is used extensively
 * throughout the IntelliJ codebase as a convenient way to obtain a logger.
 */
inline fun <reified T : Any> T.thisLogger(): Logger = object : Logger {
    override fun debug(msg: String) = println("[DEBUG][${T::class.simpleName}] $msg")
    override fun info(msg: String) = println("[INFO][${T::class.simpleName}] $msg")
    override fun warn(msg: String) = println("[WARN][${T::class.simpleName}] $msg")
    override fun error(msg: String) = System.err.println("[ERROR][${T::class.simpleName}] $msg")
}
