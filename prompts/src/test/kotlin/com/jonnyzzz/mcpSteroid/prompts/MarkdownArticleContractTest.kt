/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.prompts

import com.jonnyzzz.mcpSteroid.promptgen.buildContentParts
import com.jonnyzzz.mcpSteroid.promptgen.computeArticleFilter
import com.jonnyzzz.mcpSteroid.promptgen.parseNewFormatArticleParts
import com.jonnyzzz.mcpSteroid.prompts.generated.ResourcesIndex
import com.jonnyzzz.mcpSteroid.testHelper.ProjectHomeDirectory
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
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
 * [RD]          ← line 2: blank (IdeFilter.All) or filter like [RD]
 * Description   ← line 3: non-empty, ≤ 200 chars, no # prefix
 *               ← line 4: blank
 * ...content
 * ```
 *
 * Additionally, no bare Kotlin/Java code is allowed outside ` ```kotlin ``` ` fences.
 */
class MarkdownArticleContractTest {

    private val filterLinePattern = Regex("""^\[[A-Z,;>=<\d\s]+]$""")

    private fun newFormatArticles(): List<Path> {
        val projectHome = ProjectHomeDirectory.requireProjectHomeDirectory()
        val promptsRoot = projectHome.resolve("prompts/src/main/prompts")
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

    @Test
    fun testTitleFormat() {
        val articles = newFormatArticles()
        if (articles.isEmpty()) return // No new-format articles yet — passes trivially

        val violations = mutableListOf<String>()
        val promptsRoot = ProjectHomeDirectory.requireProjectHomeDirectory().resolve("prompts/src/main/prompts")

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
            violations.isEmpty(),
            "Title format violations in new-format articles:\n${violations.joinToString("\n")}",
        )
    }

    @Test
    fun testDescriptionFormat() {
        val articles = newFormatArticles()
        if (articles.isEmpty()) return // No new-format articles yet — passes trivially

        val violations = mutableListOf<String>()
        val promptsRoot = ProjectHomeDirectory.requireProjectHomeDirectory().resolve("prompts/src/main/prompts")

        for (file in articles) {
            val lines = Files.readAllLines(file)
            val relPath = promptsRoot.relativize(file)

            // Line 2: must be blank or a filter like [RD]
            val line2 = lines.getOrNull(1)?.trim() ?: ""
            if (line2.isNotBlank() && !filterLinePattern.matches(line2)) {
                violations.add("$relPath:2: expected blank line or filter (e.g. [RD]) after title, got: $line2")
                continue
            }

            // Description: lines 3+ until blank line (multi-line allowed)
            val descLines = mutableListOf<String>()
            var descIdx = 2
            while (descIdx < lines.size && lines[descIdx].isNotBlank()) {
                descLines.add(lines[descIdx])
                descIdx++
            }
            if (descLines.isEmpty()) {
                violations.add("$relPath:3: description is empty")
                continue
            }
            val firstDescLine = descLines[0]
            if (firstDescLine.startsWith("#")) {
                violations.add("$relPath:3: description must not start with '#': $firstDescLine")
            }
            val fullDescription = descLines.joinToString("\n")
            if (fullDescription.length > 200) {
                violations.add("$relPath:3: description exceeds 200 chars (${fullDescription.length}): ${fullDescription.take(80)}...")
            }
            if (descIdx >= lines.size || lines[descIdx].isNotBlank()) {
                violations.add("$relPath:${descIdx + 1}: expected blank line after description")
            }
        }

        assertTrue(
            violations.isEmpty(),
            "Description format violations in new-format articles:\n${violations.joinToString("\n")}",
        )
    }

    /**
     * For each new-format article, verifies that the article's parts produce content
     * matching the expected body from the source file.
     *
     * This catches bugs where the part encoder or directive-stripping logic diverges
     * from what the source file contains.
     */
    @Test
    fun testArticlePayloadMatchesSourceBody() {
        val articles = newFormatArticles()
        if (articles.isEmpty()) return

        val violations = mutableListOf<String>()
        val promptsRoot = ProjectHomeDirectory.requireProjectHomeDirectory().resolve("prompts/src/main/prompts")

        // Build URI → ArticleBase map from ResourcesIndex
        val articlesByUri = ResourcesIndex().roots
            .flatMap { it.value.articles.values }
            .associateBy { it.uri }

        for (file in articles) {
            val relPath = promptsRoot.relativize(file)
            val content = Files.readString(file)
            val lines = content.lines()
            if (lines.size < 4) continue

            // Derive URI: same logic as buildArticleUri in prompt-generator
            val folderPath = promptsRoot.relativize(file.parent).toString()
                .replace('\\', '/')
                .trimEnd('/')
            val stem = file.fileName.toString().removeSuffix(".md")
            val uri = if (folderPath.isEmpty()) "mcp" +"-steroid" + "://$stem" else "mcp" + "-steroid" + "://$folderPath/$stem"

            val article = articlesByUri[uri]
            if (article == null) {
                violations.add("$relPath: no article found for URI $uri (available: ${articlesByUri.keys.take(5)})")
                continue
            }

            // Use prompt-generator's own parser + content builder to build expected payload —
            // this stays in sync with how parts are generated at build time
            // (handles fence annotations, directive stripping, conditional blocks).
            val parsed = parseNewFormatArticleParts(content)
            val contentParts = buildContentParts(parsed)
            val expectedPayload = buildString {
                for (part in contentParts) {
                    if (part.isKotlinBlock) {
                        append("```kotlin\n")
                        append(part.content)
                        append("```")
                    } else {
                        append(part.content)
                    }
                }
            }

            // Read all parts unfiltered (concatenate all part content)
            val actualPayload = buildString {
                for (part in article.parts) {
                    when (part) {
                        is ArticlePart.KotlinCode -> {
                            append("```kotlin\n")
                            append(part.readPrompt())
                            append("```")
                        }
                        is ArticlePart.Markdown -> append(part.readPrompt())
                    }
                }
            }

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
            violations.isEmpty(),
            "Article payload mismatch for new-format articles:\n${violations.joinToString("\n")}",
        )
    }

    @Test
    fun testNoCodeOutsideKotlinFences() {
        val articles = newFormatArticles()
        if (articles.isEmpty()) return // No new-format articles yet — passes trivially

        val violations = mutableListOf<String>()
        val promptsRoot = ProjectHomeDirectory.requireProjectHomeDirectory().resolve("prompts/src/main/prompts")

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
            violations.isEmpty(),
            "Bare code outside kotlin fences in new-format articles:\n${violations.joinToString("\n")}",
        )
    }

    /**
     * Verifies that no code fences are inside blockquotes (prefixed with `> `).
     *
     * Blockquoted code fences (e.g., `> ```kotlin`) are not detected by the fence parser,
     * so they escape compilation testing. All code blocks must be at the top level.
     */
    @Test
    fun testNoBlockquotedCodeFences() {
        val articles = newFormatArticles()
        if (articles.isEmpty()) return

        val violations = mutableListOf<String>()
        val promptsRoot = ProjectHomeDirectory.requireProjectHomeDirectory().resolve("prompts/src/main/prompts")
        val blockquoteFencePattern = Regex("""^>\s*```""")

        for (file in articles) {
            val lines = Files.readAllLines(file)
            val relPath = promptsRoot.relativize(file)

            lines.forEachIndexed { index, line ->
                if (index < 4) return@forEachIndexed
                if (blockquoteFencePattern.containsMatchIn(line)) {
                    violations.add("$relPath:${index + 1}: code fence inside blockquote — remove '> ' prefix: $line")
                }
            }
        }

        assertTrue(
            violations.isEmpty(),
            "Blockquoted code fences found (these escape compilation testing):\n${violations.joinToString("\n")}",
        )
    }

    /**
     * Verifies that no kotlin code block contains large commented-out code sections.
     *
     * Commented-out code (3+ consecutive lines starting with `//` that look like real code)
     * should not be present in prompt articles. Instead, convert to descriptive prose or
     * link to a separate resource.
     */
    @Test
    fun testNoCommentedOutCodeBlocks() {
        val articles = newFormatArticles()
        if (articles.isEmpty()) return

        val violations = mutableListOf<String>()
        val promptsRoot = ProjectHomeDirectory.requireProjectHomeDirectory().resolve("prompts/src/main/prompts")
        // Patterns that look like commented-out real code (not explanatory comments)
        val codeCommentPattern = Regex("""^//\s*(import |val |var |fun |class |if \(|for \(|when |return |\.also|\.let|\?\.|runManager|withContext|ProgramRunnerUtil)""")

        for (file in articles) {
            val lines = Files.readAllLines(file)
            val relPath = promptsRoot.relativize(file)
            var inFence = false
            var consecutiveComments = 0
            var commentBlockStart = 0

            lines.forEachIndexed { index, line ->
                if (index < 4) return@forEachIndexed
                val trimmed = line.trimStart()
                when {
                    trimmed.startsWith("```") -> {
                        if (inFence && consecutiveComments >= 3) {
                            violations.add("$relPath:${commentBlockStart + 1}: $consecutiveComments consecutive commented-out code lines inside fence")
                        }
                        inFence = !inFence
                        consecutiveComments = 0
                    }
                    inFence && codeCommentPattern.containsMatchIn(trimmed) -> {
                        if (consecutiveComments == 0) commentBlockStart = index
                        consecutiveComments++
                    }
                    inFence -> {
                        if (consecutiveComments >= 3) {
                            violations.add("$relPath:${commentBlockStart + 1}: $consecutiveComments consecutive commented-out code lines inside fence")
                        }
                        consecutiveComments = 0
                    }
                }
            }
        }

        assertTrue(
            violations.isEmpty(),
            "Commented-out code blocks found in articles (remove or convert to prose):\n${violations.joinToString("\n")}",
        )
    }

    /**
     * Verifies that no article uses non-kotlin fences like ` ```text `, ` ```json `, etc.
     *
     * All code blocks in prompt articles must be ` ```kotlin ``` ` (optionally with fence
     * annotations like ` ```kotlin[IU] `). Non-kotlin fences hide code from compilation
     * testing and should not be used.
     */
    @Test
    fun testNoNonKotlinFences() {
        val articles = newFormatArticles()
        if (articles.isEmpty()) return

        val violations = mutableListOf<String>()
        val promptsRoot = ProjectHomeDirectory.requireProjectHomeDirectory().resolve("prompts/src/main/prompts")
        val nonKotlinFencePattern = Regex("""^```(?!kotlin)(\w+)""")

        for (file in articles) {
            val lines = Files.readAllLines(file)
            val relPath = promptsRoot.relativize(file)

            lines.forEachIndexed { index, line ->
                if (index < 4) return@forEachIndexed
                val trimmed = line.trimStart()
                val match = nonKotlinFencePattern.find(trimmed)
                if (match != null) {
                    violations.add("$relPath:${index + 1}: non-kotlin fence ```${match.groupValues[1]} — use ```kotlin instead")
                }
            }
        }

        assertTrue(
            violations.isEmpty(),
            "Non-kotlin fences found in articles (use ```kotlin or ```kotlin[FILTER] instead):\n${violations.joinToString("\n")}",
        )
    }

    /**
     * Verifies that article-level filter is consistent with code block filters.
     *
     * The article root filter must not be more restrictive than the weakest (most permissive)
     * code block filter. For example, an `[IU]` article with a `[RD]` code block is invalid
     * because the block would never be shown. Also, every article with code blocks must have
     * its parts' effective filters (article AND block) be satisfiable.
     */
    @Test
    fun testArticleFilterConsistency() {
        val articles = newFormatArticles()
        if (articles.isEmpty()) return

        val violations = mutableListOf<String>()
        val promptsRoot = ProjectHomeDirectory.requireProjectHomeDirectory().resolve("prompts/src/main/prompts")

        for (file in articles) {
            val content = Files.readString(file)
            val relPath = promptsRoot.relativize(file)
            val lines = content.lines()
            if (lines.size < 4) continue

            val parts = parseNewFormatArticleParts(content)
            val rootFilter = parts.rootFilter
            val contentParts = buildContentParts(parts)
            val ktParts = contentParts.filter { it.isKotlinBlock }

            // Skip articles without code blocks (overview/prose articles are valid)
            if (ktParts.isEmpty()) continue

            for ((index, ktPart) in ktParts.withIndex()) {
                val blockFilter = ktPart.filter
                // If both article and block have explicit IDE restrictions,
                // verify the intersection is non-empty (satisfiable)
                if (rootFilter is IdeFilter.Ide && blockFilter is IdeFilter.Ide) {
                    val rootCodes = rootFilter.productCodes
                    val blockCodes = blockFilter.productCodes
                    if (rootCodes.isNotEmpty() && blockCodes.isNotEmpty() && rootCodes.intersect(blockCodes).isEmpty()) {
                        violations.add(
                            "$relPath: block #$index has filter $blockCodes but article root filter is $rootCodes — " +
                                "intersection is empty, block will never be shown"
                        )
                    }
                }
            }
        }

        assertTrue(
            violations.isEmpty(),
            "Article filter inconsistencies:\n${violations.joinToString("\n")}",
        )
    }

    /**
     * Verifies that the generated article `filter` property matches the computed rule:
     * `articleFilter = declaredRootFilter AND orUnion(ktBlockFilters)`.
     *
     * TOC articles are excluded (they have no source file).
     */
    @Test
    fun testArticleFilterMatchesComputedRule() {
        val articles = newFormatArticles()
        if (articles.isEmpty()) return

        val violations = mutableListOf<String>()
        val promptsRoot = ProjectHomeDirectory.requireProjectHomeDirectory().resolve("prompts/src/main/prompts")

        val articlesByUri = ResourcesIndex().roots
            .flatMap { it.value.articles.values }
            .associateBy { it.uri }

        for (file in articles) {
            val content = Files.readString(file)
            val relPath = promptsRoot.relativize(file)
            val lines = content.lines()
            if (lines.size < 4) continue

            val folderPath = promptsRoot.relativize(file.parent).toString()
                .replace('\\', '/')
                .trimEnd('/')
            val stem = file.fileName.toString().removeSuffix(".md")
            val uri = if (folderPath.isEmpty()) "mcp" + "-steroid" + "://$stem" else "mcp" + "-steroid" + "://$folderPath/$stem"

            val article = articlesByUri[uri] ?: continue

            val parts = parseNewFormatArticleParts(content)
            val contentParts = buildContentParts(parts)
            val ktBlockFilters = contentParts.filter { it.isKotlinBlock }.map { it.filter }
            val expectedFilter = computeArticleFilter(parts.rootFilter, ktBlockFilters)

            if (article.filter != expectedFilter) {
                violations.add(
                    "$relPath: generated filter ${article.filter} does not match " +
                        "computed filter $expectedFilter (root=${parts.rootFilter}, " +
                        "ktBlocks=${ktBlockFilters.size})"
                )
            }
        }

        assertTrue(
            violations.isEmpty(),
            "Article filter does not match computed rule:\n${violations.joinToString("\n")}",
        )
    }
}
