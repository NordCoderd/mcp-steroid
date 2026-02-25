/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.prompts

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.jonnyzzz.mcpSteroid.prompts.generated.ResourcesIndex
import com.jonnyzzz.mcpSteroid.testHelper.ProjectHomeDirectory
import java.nio.file.Files
import java.nio.file.Path
import java.util.stream.Collectors

/**
 * Contract tests for new-format single-file articles.
 *
 * New-format articles are `.md` files that do NOT have a companion `-header.md` file
 * in the same directory (i.e., `<stem>-header.md` does not exist), and are not
 * `-header.md`, `-see-also.md`, or section files themselves.
 *
 * Format contract:
 * ```
 * Title         ← line 1: non-empty, ≤ 80 chars, no # prefix
 *               ← line 2: blank
 * Description   ← line 3: non-empty, ≤ 200 chars, no # prefix
 *               ← line 4: blank
 * ...content
 * ```
 *
 * Additionally, no bare Kotlin/Java code is allowed outside ` ```kotlin ``` ` fences.
 */
class MarkdownArticleContractTest : BasePlatformTestCase() {

    private fun newFormatArticles(): List<Path> {
        val projectHome = ProjectHomeDirectory.requireProjectHomeDirectory()
        val promptsRoot = projectHome.resolve("ij-plugin/src/main/prompts")
        if (!Files.isDirectory(promptsRoot)) return emptyList()

        val allMdFiles = Files.walk(promptsRoot).use { stream ->
            stream.filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".md") }
                .collect(Collectors.toList())
        }

        // Build set of all stems that have a -header.md companion
        val stemsWithHeader = allMdFiles
            .filter { it.fileName.toString().endsWith("-header.md") }
            .map { it.parent.resolve(it.fileName.toString().removeSuffix("-header.md")) }
            .toSet()

        return allMdFiles.filter { file ->
            val name = file.fileName.toString()
            // Exclude header files, see-also files, and section files
            if (name.endsWith("-header.md")) return@filter false
            if (name.endsWith("-see-also.md")) return@filter false
            if (name.contains("-section-")) return@filter false
            // Exclude root-level standalone files (not in a subfolder) — these are not articles
            if (file.parent == promptsRoot) return@filter false
            // Exclude files that have a companion -header.md (old format, not yet migrated)
            val stem = file.parent.resolve(name.removeSuffix(".md"))
            if (stem in stemsWithHeader) return@filter false
            true
        }
    }

    fun testTitleFormat() {
        val articles = newFormatArticles()
        if (articles.isEmpty()) return // No new-format articles yet — passes trivially

        val violations = mutableListOf<String>()
        val promptsRoot = ProjectHomeDirectory.requireProjectHomeDirectory().resolve("ij-plugin/src/main/prompts")

        for (file in articles) {
            val lines = Files.readAllLines(file)
            val relPath = promptsRoot.relativize(file)
            val title = lines.getOrNull(0) ?: ""
            when {
                title.isEmpty() -> violations.add("$relPath:1: title is empty")
                title.startsWith("#") -> violations.add("$relPath:1: title must not start with '#': $title")
                title.length > 80 -> violations.add("$relPath:1: title exceeds 80 chars (${title.length}): $title")
            }
        }

        assertTrue(
            "Title format violations in new-format articles:\n${violations.joinToString("\n")}",
            violations.isEmpty()
        )
    }

    fun testDescriptionFormat() {
        val articles = newFormatArticles()
        if (articles.isEmpty()) return // No new-format articles yet — passes trivially

        val violations = mutableListOf<String>()
        val promptsRoot = ProjectHomeDirectory.requireProjectHomeDirectory().resolve("ij-plugin/src/main/prompts")

        for (file in articles) {
            val lines = Files.readAllLines(file)
            val relPath = promptsRoot.relativize(file)

            val line2 = lines.getOrNull(1) ?: ""
            if (line2.isNotBlank()) {
                violations.add("$relPath:2: expected blank line after title, got: $line2")
                continue
            }
            val description = lines.getOrNull(2) ?: ""
            when {
                description.isEmpty() -> violations.add("$relPath:3: description is empty")
                description.startsWith("#") -> violations.add("$relPath:3: description must not start with '#': $description")
                description.length > 200 -> violations.add("$relPath:3: description exceeds 200 chars (${description.length}): $description")
            }
            val line4 = lines.getOrNull(3) ?: ""
            if (line4.isNotBlank()) {
                violations.add("$relPath:4: expected blank line after description, got: $line4")
            }
        }

        assertTrue(
            "Description format violations in new-format articles:\n${violations.joinToString("\n")}",
            violations.isEmpty()
        )
    }

    /**
     * For each new-format article, verifies that `payload.readPrompt()` equals the expected body:
     * - Lines 5+ of the source file (after title, blank, description, blank)
     * - `# See also` section removed
     * - Directive lines (`###_NO_AUTO_TOC_###`, `###_EXCLUDE_FROM_AUTO_TOC_###`) stripped
     *
     * This catches bugs where the payload encoder or directive-stripping logic diverges
     * from what the source file contains.
     */
    fun testArticlePayloadMatchesSourceBody() {
        val articles = newFormatArticles()
        if (articles.isEmpty()) return

        val violations = mutableListOf<String>()
        val promptsRoot = ProjectHomeDirectory.requireProjectHomeDirectory().resolve("ij-plugin/src/main/prompts")

        // Build URI → ArticleBase map from ResourcesIndex
        val articlesByUri = ResourcesIndex().roots
            .flatMap { it.value.articles.values }
            .associateBy { it.uri }

        val directives = setOf("###_NO_AUTO_TOC_###", "###_EXCLUDE_FROM_AUTO_TOC_###")

        for (file in articles) {
            val relPath = promptsRoot.relativize(file)
            val content = Files.readString(file)
            val lines = content.lines()
            if (lines.size < 4) continue

            // Derive URI: same logic as buildArticleUri in buildSrc
            val folderPath = promptsRoot.relativize(file.parent).toString()
                .replace('\\', '/')
                .trimEnd('/')
            val stem = file.fileName.toString().removeSuffix(".md")
            val uri = if (folderPath.isEmpty()) "mcp-steroid://$stem" else "mcp-steroid://$folderPath/$stem"

            val article = articlesByUri[uri]
            if (article == null) {
                violations.add("$relPath: no article found for URI $uri (available: ${articlesByUri.keys.take(5)})")
                continue
            }

            // Extract body: lines 5+ joined, then strip # See also section
            val bodyAndSeeAlso = lines.drop(4).joinToString("\n")
            val seeAlsoMarker = "\n\n# See also\n"
            val seeAlsoIdx = bodyAndSeeAlso.indexOf(seeAlsoMarker)
            val body = if (seeAlsoIdx >= 0) bodyAndSeeAlso.substring(0, seeAlsoIdx) else bodyAndSeeAlso

            // Strip directive lines (same logic as generateNewFormatParts.stripDirective)
            val expectedPayload = if (directives.any { body.contains(it) }) {
                body.lines().filter { it !in directives }.joinToString("\n")
            } else body

            val actualPayload = article.payload.readPrompt()
            if (actualPayload != expectedPayload) {
                val firstDiff = expectedPayload.zip(actualPayload).indexOfFirst { (a, b) -> a != b }
                violations.add(
                    "$relPath: payload mismatch. " +
                        "expected length=${expectedPayload.length}, actual=${actualPayload.length}. " +
                        "First diff at char $firstDiff"
                )
            }
        }

        assertTrue(
            "Article payload mismatch for new-format articles:\n${violations.joinToString("\n")}",
            violations.isEmpty()
        )
    }

    fun testNoCodeOutsideKotlinFences() {
        val articles = newFormatArticles()
        if (articles.isEmpty()) return // No new-format articles yet — passes trivially

        val violations = mutableListOf<String>()
        val promptsRoot = ProjectHomeDirectory.requireProjectHomeDirectory().resolve("ij-plugin/src/main/prompts")

        // Patterns that indicate bare Kotlin/Java code outside fences
        val codePatterns = listOf(
            Regex("""^import \p{L}"""),
            Regex("""^val """),
            Regex("""^var """),
            Regex("""^fun """),
            Regex("""^class """),
            Regex("""^object """),
            Regex("""^interface """),
            Regex("""^data class """),
            Regex("""^override fun """),
            Regex("""^private fun """),
            Regex("""^public fun """),
        )

        for (file in articles) {
            val lines = Files.readAllLines(file)
            val relPath = promptsRoot.relativize(file)
            var inFence = false

            // Skip header+blank+description+blank (lines 0..3) — content starts at line 4
            lines.forEachIndexed { index, line ->
                if (index < 4) return@forEachIndexed
                val trimmed = line.trimStart()
                when {
                    trimmed.startsWith("```") -> inFence = !inFence
                    !inFence -> {
                        for (pattern in codePatterns) {
                            if (pattern.containsMatchIn(line)) {
                                violations.add("$relPath:${index + 1}: bare code outside fence: $line")
                                break
                            }
                        }
                    }
                }
            }
        }

        assertTrue(
            "Bare code outside kotlin fences in new-format articles:\n${violations.joinToString("\n")}",
            violations.isEmpty()
        )
    }
}
