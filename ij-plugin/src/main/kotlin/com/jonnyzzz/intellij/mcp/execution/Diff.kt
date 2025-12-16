/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.intellij.mcp.execution

import com.intellij.diff.comparison.ComparisonManager
import com.intellij.diff.comparison.ComparisonPolicy
import com.intellij.diff.fragments.LineFragment
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.progress.DumbProgressIndicator
import com.intellij.openapi.progress.ProcessCanceledException

object Diff {
    private val log = thisLogger()

    /**
     * Generate a unified diff between original and edited code.
     */
    fun generateUnifiedDiff(original: String, edited: String): String {
        val originalLines = original.lines()
        val editedLines = edited.lines()

        return try {
            val fragments = ComparisonManager.getInstance().compareLines(
                original,
                edited,
                ComparisonPolicy.DEFAULT,
                DumbProgressIndicator.INSTANCE
            )

            buildUnifiedDiff(originalLines, editedLines, fragments)
        } catch (e: ProcessCanceledException) {
            throw e
        } catch (e: Exception) {
            log.warn("Failed to generate diff: ${e.message}", e)
            // Fallback to simple diff
            buildSimpleDiff(originalLines, editedLines)
        }
    }

    private fun buildUnifiedDiff(
        originalLines: List<String>,
        editedLines: List<String>,
        fragments: List<LineFragment>
    ): String = buildString {
        appendLine("--- original")
        appendLine("+++ edited")

        if (fragments.isEmpty()) {
            appendLine("@@ -1,${originalLines.size} +1,${editedLines.size} @@")
            originalLines.forEach { appendLine("-$it") }
            editedLines.forEach { appendLine("+$it") }
            return@buildString
        }

        for (fragment in fragments) {
            val startLine1 = fragment.startLine1 + 1
            val endLine1 = fragment.endLine1
            val startLine2 = fragment.startLine2 + 1
            val endLine2 = fragment.endLine2

            val count1 = endLine1 - fragment.startLine1
            val count2 = endLine2 - fragment.startLine2

            appendLine("@@ -$startLine1,$count1 +$startLine2,$count2 @@")

            // Add context and changes
            for (i in fragment.startLine1 until fragment.endLine1) {
                if (i < originalLines.size) {
                    appendLine("-${originalLines[i]}")
                }
            }
            for (i in fragment.startLine2 until fragment.endLine2) {
                if (i < editedLines.size) {
                    appendLine("+${editedLines[i]}")
                }
            }
        }
    }

    private fun buildSimpleDiff(originalLines: List<String>, editedLines: List<String>): String = buildString {
        appendLine("--- original")
        appendLine("+++ edited")
        appendLine("@@ -1,${originalLines.size} +1,${editedLines.size} @@")

        // Simple line-by-line comparison
        val maxLines = maxOf(originalLines.size, editedLines.size)
        for (i in 0 until maxLines) {
            val origLine = originalLines.getOrNull(i)
            val editLine = editedLines.getOrNull(i)

            when {
                origLine == editLine -> appendLine(" $origLine")
                origLine != null && editLine != null -> {
                    appendLine("-$origLine")
                    appendLine("+$editLine")
                }

                origLine != null -> appendLine("-$origLine")
                editLine != null -> appendLine("+$editLine")
            }
        }
    }
}