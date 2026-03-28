/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.koltinc

/**
 * Maps wrapped-file line numbers back to original user-code line numbers.
 *
 * When user code is wrapped by [CodeWrapperForCompilation.wrap], imports and boilerplate
 * are prepended, shifting all line numbers. This class remaps compiler error references
 * (e.g., `input.kt:23:5: error: ...`) so agents see user-relative line numbers.
 */
class LineMapping(private val wrappedToOriginal: Map<Int, Int>) {

    /**
     * Remap line references in compiler output from wrapped-file lines to user-code lines.
     * Matches patterns like `input.kt:LINE:COL:` and replaces LINE with the user-relative number.
     * Lines not in the mapping (wrapper boilerplate) are left as-is.
     */
    fun remapCompilerOutput(output: String, fileName: String = "input.kt"): String {
        val pattern = Regex("""${Regex.escape(fileName)}:(\d+):(\d+):""")
        return output.replace(pattern) { match ->
            val wrappedLine = match.groupValues[1].toInt()
            val col = match.groupValues[2]
            val originalLine = wrappedToOriginal[wrappedLine]
            if (originalLine != null) {
                "$fileName:$originalLine:$col:"
            } else {
                match.value
            }
        }
    }

    companion object {
        /** A no-op mapping that leaves all line numbers unchanged. */
        val IDENTITY = LineMapping(emptyMap())
    }
}
