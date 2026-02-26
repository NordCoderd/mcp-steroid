/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.server

import com.intellij.openapi.application.ApplicationInfo

/**
 * Processes product-conditional blocks in prompt content at runtime.
 *
 * Directive syntax (each directive must be on its own line):
 * ```
 * ###_IF_RIDER_###
 * Rider-specific content
 * ###_ELSE_###              (optional)
 * Content for all other IDEs
 * ###_END_IF_###
 * ```
 *
 * When the current IDE matches the product token, the IF block is kept and ELSE removed.
 * When it does not match, the IF block is removed and ELSE kept.
 * Directive lines themselves are always removed from the output.
 *
 * Supported product tokens and their IntelliJ product codes:
 * - RIDER → RD
 * - IDEA → IU, IC
 * - GOLAND → GO
 * - CLION → CL
 * - PYCHARM → PY, PC
 * - WEBSTORM → WS
 * - RUBYMINE → RM
 * - DATAGRIP → DB
 */
object ProductConditionals {

    private val PRODUCT_TOKEN_TO_CODES = mapOf(
        "RIDER" to setOf("RD"),
        "IDEA" to setOf("IU", "IC"),
        "GOLAND" to setOf("GO"),
        "CLION" to setOf("CL"),
        "PYCHARM" to setOf("PY", "PC"),
        "WEBSTORM" to setOf("WS"),
        "RUBYMINE" to setOf("RM"),
        "DATAGRIP" to setOf("DB"),
    )

    private val IF_PATTERN = Regex("""^###_IF_(\w+)_###$""")
    private const val ELSE_MARKER = "###_ELSE_###"
    private const val END_IF_MARKER = "###_END_IF_###"

    /**
     * Quick check: does the content contain any conditional directives?
     */
    fun hasConditionals(content: String): Boolean = content.contains("###_IF_")

    /**
     * Process conditional blocks based on the current IDE product.
     * Returns the content with conditionals resolved and directive lines removed.
     */
    fun process(content: String): String {
        if (!hasConditionals(content)) return content
        val productCode = ApplicationInfo.getInstance().build.productCode
        return processForProduct(content, productCode)
    }

    /**
     * Process conditional blocks for a specific product code.
     * Visible for testing.
     */
    fun processForProduct(content: String, productCode: String): String {
        if (!hasConditionals(content)) return content

        val lines = content.lines()
        val output = mutableListOf<String>()
        var i = 0

        while (i < lines.size) {
            val trimmed = lines[i].trim()
            val match = IF_PATTERN.matchEntire(trimmed)

            if (match != null) {
                val productToken = match.groupValues[1]
                val productCodes = PRODUCT_TOKEN_TO_CODES[productToken]
                val matches = productCodes != null && productCode in productCodes

                val ifLines = mutableListOf<String>()
                val elseLines = mutableListOf<String>()
                var inElse = false
                i++ // skip IF directive line

                while (i < lines.size) {
                    val current = lines[i].trim()
                    when (current) {
                        ELSE_MARKER -> {
                            inElse = true
                            i++
                        }

                        END_IF_MARKER -> {
                            i++
                            break
                        }

                        else -> {
                            if (inElse) elseLines.add(lines[i])
                            else ifLines.add(lines[i])
                            i++
                        }
                    }
                }

                output.addAll(if (matches) ifLines else elseLines)
            } else {
                output.add(lines[i])
                i++
            }
        }

        return output.joinToString("\n")
    }
}
