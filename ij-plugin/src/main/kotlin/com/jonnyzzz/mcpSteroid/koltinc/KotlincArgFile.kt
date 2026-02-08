/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.koltinc

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

/**
 * Quotes a single argument for a kotlinc @argfile.
 *
 * kotlinc's argfile parser splits on whitespace outside quotes.
 * Inside double quotes, `\` is an escape character: `\\` → `\`, `\"` → `"`.
 * Outside quotes, `\` is a literal character (important for Windows paths).
 *
 * Only wraps the argument in quotes when whitespace or quote characters are present.
 * Backslashes are only escaped inside quotes (because they are only special
 * inside quotes in kotlinc's parser), so unquoted Windows paths stay intact.
 */
fun quoteForKotlinc(arg: String): String {
    if (arg.none { it.isWhitespace() || it == '"' || it == '\'' }) return arg

    val sb = StringBuilder(arg.length + 2)
    sb.append('"')
    for (c in arg) {
        if (c == '\\' || c == '"') sb.append('\\')
        sb.append(c)
    }
    sb.append('"')
    return sb.toString()
}

/**
 * Writes a kotlinc-compatible argument file.
 *
 * Each argument is quoted using [quoteForKotlinc] and written on its own line.
 * This replaces [com.intellij.execution.CommandLineWrapperUtil.writeArgumentsFile]
 * which uses per-character quoting incompatible with kotlinc's argfile parser.
 */
fun writeKotlincArgFile(argFile: Path, args: List<String>) {
    val content = args.joinToString("\n") { quoteForKotlinc(it) }
    Files.writeString(argFile, content, StandardCharsets.UTF_8)
}
