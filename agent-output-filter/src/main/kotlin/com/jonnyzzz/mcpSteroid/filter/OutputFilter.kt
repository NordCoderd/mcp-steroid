/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.filter

import java.io.InputStream
import java.io.OutputStream

/**
 * Base interface for agent output filters.
 *
 * Filters read NDJSON from stdin, parse events, and output human-readable text to stdout.
 */
interface OutputFilter {
    /**
     * Process input stream and write filtered output.
     *
     * @param input Input stream (typically stdin) with NDJSON content
     * @param output Output stream (typically stdout) for human-readable text
     */
    fun process(input: InputStream, output: OutputStream)
}
