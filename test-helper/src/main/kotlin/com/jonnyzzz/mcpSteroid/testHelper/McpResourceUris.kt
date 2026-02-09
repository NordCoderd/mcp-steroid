/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.testHelper

object McpResourceUris {
    private const val SCHEME = "mcp-steroid"
    private const val SCHEME_SEPARATOR = "://"

    val debuggerOverview: String = buildUri("debugger/overview")

    private fun buildUri(path: String): String = "$SCHEME$SCHEME_SEPARATOR$path"
}
