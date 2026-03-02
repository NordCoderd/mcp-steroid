/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.promptgen

/**
 * Metadata parsed from a ` ```kotlin[...] ` fence annotation.
 *
 * Format: ` ```kotlin[product_codes;version_constraint] `
 * - Product codes: comma-separated — `IU`, `RD`, `CL`, `GO`, `PY`, `WS`, `RM`, `DB`
 * - Version constraint: `>=253` or `<=261` (baseline version number)
 * - Semicolon separates products from version
 *
 * Examples:
 * - ` ```kotlin ` → [DEFAULT]
 * - ` ```kotlin[RD] ` → Rider only
 * - ` ```kotlin[IU,RD] ` → IDEA and Rider
 * - ` ```kotlin[RD;>=254] ` → Rider 254+
 * - ` ```kotlin[;>=253] ` → all IDEs, version 253+
 */
data class FenceMetadata(
    val productCodes: Set<String>,
    val minVersion: Int?,
    val maxVersion: Int?,
) {
    val isDefault: Boolean get() = productCodes.isEmpty() && minVersion == null && maxVersion == null

    fun toFenceSuffix(): String {
        if (isDefault) return ""
        val parts = mutableListOf<String>()
        parts += productCodes.sorted().joinToString(",")
        val versionParts = mutableListOf<String>()
        if (minVersion != null) versionParts += ">=$minVersion"
        if (maxVersion != null) versionParts += "<=$maxVersion"
        if (versionParts.isNotEmpty()) {
            parts += versionParts.joinToString(",")
        }
        val inner = parts.joinToString(";").trimStart(';')
        return "[$inner]"
    }

    companion object {
        val DEFAULT = FenceMetadata(emptySet(), null, null)

        private val VALID_PRODUCT_CODES = setOf("IU", "RD", "CL", "GO", "PY", "WS", "RM", "DB")

        /**
         * Parses the bracket content from a ` ```kotlin[...] ` annotation.
         *
         * @param bracket the content inside `[...]`, e.g. "RD;>=253" or "IU,RD"
         */
        fun parse(bracket: String): FenceMetadata {
            val trimmed = bracket.trim()
            if (trimmed.isEmpty()) return DEFAULT

            val semicolonParts = trimmed.split(";", limit = 2)
            val productPart = semicolonParts[0].trim()
            val versionPart = if (semicolonParts.size > 1) semicolonParts[1].trim() else ""

            val productCodes = if (productPart.isNotEmpty()) {
                productPart.split(",").map { it.trim() }.filter { it.isNotEmpty() }.toSet().also { codes ->
                    for (code in codes) {
                        require(code in VALID_PRODUCT_CODES) {
                            "Unknown product code '$code' in fence metadata. Valid codes: $VALID_PRODUCT_CODES"
                        }
                    }
                }
            } else emptySet()

            var minVersion: Int? = null
            var maxVersion: Int? = null

            if (versionPart.isNotEmpty()) {
                for (constraint in versionPart.split(",")) {
                    val c = constraint.trim()
                    when {
                        c.startsWith(">=") -> {
                            minVersion = c.removePrefix(">=").trim().toIntOrNull()
                                ?: error("Invalid version in fence metadata: '$c'")
                        }
                        c.startsWith("<=") -> {
                            maxVersion = c.removePrefix("<=").trim().toIntOrNull()
                                ?: error("Invalid version in fence metadata: '$c'")
                        }
                        else -> error("Invalid version constraint in fence metadata: '$c'. Use >=NNN or <=NNN")
                    }
                }
            }

            return FenceMetadata(productCodes, minVersion, maxVersion)
        }
    }
}

/**
 * Extracts the bracket annotation from a kotlin fence line, if present.
 *
 * Given a line like ` ```kotlin[RD;>=253] `, returns `"RD;>=253"`.
 * Given ` ```kotlin `, returns `null`.
 */
fun extractFenceBracket(line: String): String? {
    val trimmed = line.trimStart()
    if (!trimmed.startsWith("```kotlin")) return null
    val afterKotlin = trimmed.removePrefix("```kotlin")
    if (!afterKotlin.startsWith("[")) return null
    val closeBracket = afterKotlin.indexOf(']')
    if (closeBracket < 0) return null
    return afterKotlin.substring(1, closeBracket)
}
