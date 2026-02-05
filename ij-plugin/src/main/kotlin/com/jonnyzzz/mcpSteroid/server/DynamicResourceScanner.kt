/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.server

import com.jonnyzzz.mcpSteroid.prompts.PromptRegistry

/**
 * Represents a dynamically discovered resource from the plugin's resource files.
 */
data class DynamicResource(
    /** Unique identifier derived from filename (e.g., "extract-method") */
    val id: String,
    /** Display name parsed from KDoc or generated from filename */
    val name: String,
    /** Description parsed from KDoc comment */
    val description: String,
    /** Path to the resource file within the plugin jar */
    val resourcePath: String,
    /** MIME type based on file extension */
    val mimeType: String,
)

/**
 * Parses resource files for metadata from KDoc comments.
 *
 * Resources are discovered by:
 * 1. Loading the file content from plugin resources
 * 2. Parsing KDoc comments from .kts files for metadata
 */
object DynamicResourceScanner {

    /**
     * Load a resource and parse its metadata from the KDoc comment.
     *
     * @param resourceDir The resource directory (e.g., "/ide-examples")
     * @param fileName The file name (e.g., "extract-method.kts")
     * @return DynamicResource with parsed metadata, or null if file not found
     */
    fun loadResource(resourceDir: String, fileName: String): DynamicResource? {
        val resourcePath = "$resourceDir/$fileName"
        val content = loadResourceContent(resourcePath) ?: return null

        val parsed = parseKDoc(content)
        val id = fileName.substringBeforeLast('.')
        val extension = fileName.substringAfterLast('.')
        val mimeType = when (extension) {
            "kts" -> "text/x-kotlin"
            "md" -> "text/markdown"
            else -> "text/plain"
        }

        return DynamicResource(
            id = id,
            name = parsed.name ?: formatNameFromId(id),
            description = parsed.description,
            resourcePath = resourcePath,
            mimeType = mimeType
        )
    }

    /**
     * Load resources for a list of file names.
     *
     * @param resourceDir The resource directory
     * @param fileNames List of file names to load
     * @return List of successfully loaded resources
     */
    fun loadResources(resourceDir: String, fileNames: List<String>): List<DynamicResource> {
        return fileNames.mapNotNull { loadResource(resourceDir, it) }
    }

    /**
     * Parsed KDoc comment data.
     */
    data class ParsedKDoc(
        val name: String?,
        val description: String,
    )

    /**
     * Parse KDoc comment from a .kts file content.
     * Extracts the title from "IDE: Title" pattern and the rest as description.
     */
    fun parseKDoc(content: String): ParsedKDoc {
        val kdocMatch = Regex("""/\*\*\s*([\s\S]*?)\s*\*/""").find(content)
            ?: return ParsedKDoc(null, "")

        val kdocContent = kdocMatch.groupValues[1]
        val lines = kdocContent.lines()
            .map { it.trimStart().removePrefix("*").trim() }
            .filter { it.isNotEmpty() }

        if (lines.isEmpty()) return ParsedKDoc(null, "")

        // First line might be the title (e.g., "IDE: Extract Method")
        val firstLine = lines.first()
        val titleMatch = Regex("""^(?:IDE:\s*)?(.+)$""").find(firstLine)
        val name = titleMatch?.groupValues?.get(1)?.takeIf {
            !it.contains("Parameters") && !it.contains("IntelliJ API")
        }

        val descriptionLines = if (name != null) lines.drop(1) else lines
        val description = descriptionLines
            .joinToString("\n")
            .trim()

        return ParsedKDoc(name, description)
    }

    /**
     * Convert an ID like "extract-method" to a display name like "Extract Method".
     */
    fun formatNameFromId(id: String): String {
        return id.split("-", "_")
            .joinToString(" ") { word ->
                word.replaceFirstChar { it.uppercaseChar() }
            }
    }

    /**
     * Load content from a prompt resource.
     * The path should be relative (e.g., "ide-examples/extract-method.kts").
     */
    fun loadResourceContent(resourcePath: String): String? {
        // Normalize path: remove leading slash if present
        val normalizedPath = resourcePath.removePrefix("/")
        return PromptRegistry.getContent(normalizedPath)
    }
}
