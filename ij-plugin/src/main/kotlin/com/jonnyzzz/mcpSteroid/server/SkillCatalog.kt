/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.server

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service

data class SkillFrontmatter(
    val name: String?,
    val description: String?,
)

data class SkillDocument(
    val descriptor: SkillDescriptor,
    val promptName: String,
    val description: String?,
    val content: String,
    val contentWithoutFrontmatter: String,
)

@Service(Service.Level.APP)
class SkillCatalog {
    private val skillResource: SkillResource
        get() = service()

    private val skills: List<SkillDocument> by lazy {
        skillResources.all.map { descriptor -> loadSkill(descriptor) }
    }

    fun listSkills(): List<SkillDocument> = skills

    fun findByPromptName(name: String): SkillDocument? {
        return skills.firstOrNull { it.promptName == name || it.descriptor.id == name }
    }

    private fun loadSkill(descriptor: SkillDescriptor): SkillDocument {
        val content = loadSkillContent(descriptor)
        val parsed = parseSkillFrontmatter(content)
        val promptName = parsed.frontmatter?.name?.takeIf { it.isNotBlank() } ?: descriptor.id
        val description = parsed.frontmatter?.description
        return SkillDocument(
            descriptor = descriptor,
            promptName = promptName,
            description = description,
            content = content,
            contentWithoutFrontmatter = parsed.body,
        )
    }

    private fun loadSkillContent(descriptor: SkillDescriptor): String {
        if (descriptor == skillResources.main) {
            return skillResource.loadSkillMd()
        }

        return javaClass.getResourceAsStream(descriptor.resourcePath)
            ?.bufferedReader()
            ?.readText()
            ?: error("Skill resource not found: ${descriptor.resourcePath}")
    }

}

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

    val opening = lines.first()
    if (!isYamlDelimiterLine(opening) && !isTomlDelimiterLine(opening)) {
        return FrontmatterParseResult(frontmatter = null, body = normalized.trimStart())
    }

    val isYaml = isYamlDelimiterLine(opening)
    val closingIndex = lines.drop(1).indexOfFirst { line ->
        if (isYaml) isYamlClosingDelimiterLine(line) else isTomlDelimiterLine(line)
    }
    if (closingIndex < 0) {
        return FrontmatterParseResult(frontmatter = null, body = normalized.trimStart())
    }

    val closingLineIndex = closingIndex + 1
    val frontmatterLines = lines.subList(1, closingLineIndex)
    val bodyLines = lines.drop(closingLineIndex + 1)

    val name = if (isYaml) readYamlFrontmatterValue(frontmatterLines, "name") else readTomlFrontmatterValue(frontmatterLines, "name")
    val description = if (isYaml) readYamlFrontmatterValue(frontmatterLines, "description") else readTomlFrontmatterValue(frontmatterLines, "description")

    return FrontmatterParseResult(
        frontmatter = SkillFrontmatter(name = name, description = description),
        body = bodyLines.joinToString("\n").trimStart()
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
