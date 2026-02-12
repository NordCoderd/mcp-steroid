/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.filter

import java.io.InputStream
import java.io.OutputStream

/**
 * Filter for Gemini CLI text output with ANSI escape codes.
 *
 * Gemini CLI with `--screen-reader true` outputs text-based progress with ANSI escape codes.
 * Unlike Claude and Codex, Gemini produces human-readable text rather than structured JSON.
 *
 * This filter:
 * - Strips ANSI escape sequences (colors, cursor positioning, etc.)
 * - Filters blank lines, decorative separators, spinner dots
 * - Deduplicates consecutive identical lines
 * - Highlights tool activity with >> prefix
 */
class GeminiFilter : OutputFilter {
    // Regex to strip ANSI escape sequences
    // Covers: CSI (colors, cursor), OSC (terminal title), charset selection, DEC modes
    private val ansiRegex = Regex(
        """\x1b\[[0-9;]*[a-zA-Z]|\x1b\][^\x07]*\x07|\x1b[()][AB012]|\x1b\[\?[0-9;]*[hl]"""
    )

    // Patterns for noisy lines to suppress (using matches for full line match)
    private val noisePatterns = listOf(
        Regex("""^\s*$"""),                    // Empty lines
        Regex("""^[\s\-=_*]+$"""),             // Decorative separators
        Regex("""^\s*\.+\s*$"""),              // Spinner dots
    )

    // Pattern to check if line contains carriage return (use containsMatchIn)
    private val carriageReturnPattern = Regex("""\r""")

    // Patterns that indicate tool/MCP activity (highlight these)
    private val toolPatterns = listOf(
        Regex("""(?:calling|using|executing|running)\s+(?:tool|function|mcp)""", RegexOption.IGNORE_CASE),
        Regex("""steroid_execute_code""", RegexOption.IGNORE_CASE),
        Regex("""read_mcp_resource""", RegexOption.IGNORE_CASE),
        Regex("""mcp_tool_call""", RegexOption.IGNORE_CASE),
        Regex("""(?:Execution ID|execution_id):\s*eid_[A-Za-z0-9_-]+""", RegexOption.IGNORE_CASE),
        Regex("""^\s*(?:>>|<<)\s+\w+"""),      // Tool call (>>) or result (<<) prefix
        Regex("""(?:Tool|Function)\s+(?:result|output|completed)""", RegexOption.IGNORE_CASE),
        Regex("""(?:Reading|Writing|Editing)\s+(?:file|resource):""", RegexOption.IGNORE_CASE),
        Regex("""(?:Bash|Command)\s+(?:execution|output):""", RegexOption.IGNORE_CASE),
    )

    override fun process(input: InputStream, output: OutputStream) {
        val writer = output.bufferedWriter()
        var prevLine: String? = null

        input.bufferedReader().useLines { lines ->
            for (rawLine in lines) {
                // Strip ANSI escape codes
                val line = ansiRegex.replace(rawLine, "").trimEnd()

                // Skip noise
                if (noisePatterns.any { it.matches(line) }) {
                    continue
                }

                // Skip lines with carriage return (progress overwrites)
                if (carriageReturnPattern.containsMatchIn(line)) {
                    continue
                }

                // Deduplicate identical consecutive lines
                if (line == prevLine) {
                    continue
                }
                prevLine = line

                // Highlight tool calls with >> prefix for consistency with Claude/Codex filters
                if (toolPatterns.any { it.containsMatchIn(line) }) {
                    writer.write(">> $line")
                } else {
                    writer.write(line)
                }
                writer.newLine()
                writer.flush()
            }
        }

        writer.flush()
    }
}
