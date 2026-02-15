/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.testHelper

import com.jonnyzzz.mcpSteroid.filter.OutputFilter
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/**
 * Pipe a string through an [OutputFilter] and return the filtered result.
 */
fun OutputFilter.filterText(input: String): String {
    val bais = ByteArrayInputStream(input.toByteArray())
    val baos = ByteArrayOutputStream()
    process(bais, baos)
    return baos.toString(Charsets.UTF_8).trim()
}
