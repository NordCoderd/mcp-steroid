/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.server

data class SkillFrontmatter(
    val name: String?,
    val description: String?,
)

internal data class FrontmatterParseResult(
    val frontmatter: SkillFrontmatter?,
    val body: String,
)

internal fun parseSkillFrontmatter(content: String): FrontmatterParseResult {
    val normalized = content.replace("\r\n", "\n")
    val lines = normalized.lines()
    if (lines.isEmpty()) {
        return FrontmatterParseResult(frontmatter = null, body = normalized.trimStart())
    }

    // Find the opening delimiter line — may be after title/description from readPayload()
    val openingIndex = lines.indexOfFirst { isYamlDelimiterLine(it) || isTomlDelimiterLine(it) }
    if (openingIndex < 0) {
        return FrontmatterParseResult(frontmatter = null, body = normalized.trimStart())
    }

    val opening = lines[openingIndex]
    val isYaml = isYamlDelimiterLine(opening)
    val closingIndex = lines.drop(openingIndex + 1).indexOfFirst { line ->
        if (isYaml) isYamlClosingDelimiterLine(line) else isTomlDelimiterLine(line)
    }
    if (closingIndex < 0) {
        return FrontmatterParseResult(frontmatter = null, body = normalized.trimStart())
    }

    val closingLineIndex = openingIndex + 1 + closingIndex
    val frontmatterLines = lines.subList(openingIndex + 1, closingLineIndex)

    val name = if (isYaml) readYamlFrontmatterValue(frontmatterLines, "name") else readTomlFrontmatterValue(frontmatterLines, "name")
    val description = if (isYaml) readYamlFrontmatterValue(frontmatterLines, "description") else readTomlFrontmatterValue(frontmatterLines, "description")

    val preLines = lines.subList(0, openingIndex)
    val postLines = lines.drop(closingLineIndex + 1)
    val body = (preLines + postLines).joinToString("\n").trimStart()

    return FrontmatterParseResult(
        frontmatter = SkillFrontmatter(name = name, description = description),
        body = body
    )
}

private fun isYamlDelimiterLine(line: String): Boolean {
    return line.length >= 3 && line.all { it == '-' }
}

private fun isYamlClosingDelimiterLine(line: String): Boolean {
    return isYamlDelimiterLine(line) || (line.length >= 3 && line.all { it == '.' })
}

private fun isTomlDelimiterLine(line: String): Boolean {
    return line.length >= 3 && line.all { it == '+' }
}

internal fun readYamlFrontmatterValue(lines: List<String>, key: String): String? {
    var index = 0
    while (index < lines.size) {
        val line = lines[index].trimEnd()
        if (line.startsWith("$key:")) {
            val value = line.substringAfter("$key:").trim()
            if (value == "|" || value == ">") {
                val buffer = StringBuilder()
                index++
                while (index < lines.size && lines[index].startsWith(" ")) {
                    if (buffer.isNotEmpty()) buffer.append('\n')
                    buffer.append(lines[index].trim())
                    index++
                }
                return buffer.toString().takeIf { it.isNotBlank() }
            }

            return value.trim().trim('"').takeIf { it.isNotBlank() }
        }
        index++
    }
    return null
}

internal fun readTomlFrontmatterValue(lines: List<String>, key: String): String? {
    for (line in lines) {
        val trimmed = line.trim()
        if (trimmed.startsWith(key)) {
            val afterKey = trimmed.removePrefix(key).trimStart()
            if (!afterKey.startsWith("=")) continue
            val value = afterKey.removePrefix("=").trim()
            if (value.isEmpty()) return null
            return value.trim().trim('"', '\'')
                .takeIf { it.isNotBlank() }
        }
    }
    return null
}
