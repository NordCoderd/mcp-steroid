/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.filter

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import java.io.BufferedWriter
import java.io.InputStream
import java.io.OutputStream

/**
 * Base implementation for NDJSON output filters.
 *
 * Provides the common read-parse-dispatch loop. Subclasses implement
 * [processEvent] to handle parsed JSON events.
 */
abstract class AbstractOutputFilter : AgentProgressOutputFilter {

    override fun process(input: InputStream, output: OutputStream) {
        val writer = output.bufferedWriter()
        beforeProcessing(writer)

        input.bufferedReader().useLines { lines ->
            for (line in lines) {
                val trimmed = line.trim()
                if (trimmed.isEmpty()) continue

                if (!trimmed.startsWith('{')) {
                    onNonJsonLine(line, writer)
                    continue
                }

                try {
                    val event = filterJson.parseToJsonElement(trimmed).jsonObject
                    processEvent(event, writer)
                } catch (_: Exception) {
                    onMalformedJson(line, writer)
                }
            }
        }

        afterProcessing(writer)
        writer.flush()
    }

    /** Handle a single parsed JSON event. */
    protected abstract fun processEvent(event: JsonObject, writer: BufferedWriter)

    /** Called before the first line is read. */
    protected open fun beforeProcessing(writer: BufferedWriter) {}

    /** Called after all lines have been processed, before final flush. */
    protected open fun afterProcessing(writer: BufferedWriter) {}

    /** Handle a line that does not start with '{'. Default: pass through. */
    protected open fun onNonJsonLine(line: String, writer: BufferedWriter) {
        writer.writeLine(line)
    }

    /** Handle a line that starts with '{' but fails JSON parsing. Default: pass through. */
    protected open fun onMalformedJson(line: String, writer: BufferedWriter) {
        writer.writeLine(line)
    }
}
