/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.testHelper

import java.io.File

internal fun createTempDirectory(prefix: String): File {
    val tempDir = File(System.getProperty("java.io.tmpdir"), "docker-$prefix-${System.currentTimeMillis()}")
    tempDir.mkdirs()
    return tempDir
}


fun String.titleCase() = replaceFirstChar { it.titlecase() }
