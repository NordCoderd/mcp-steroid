/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.intellij.mcp.review

import com.intellij.diff.comparison.ComparisonManager
import com.intellij.diff.comparison.ComparisonPolicy
import com.intellij.diff.fragments.LineFragment
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.progress.DumbProgressIndicator
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.registry.Registry
import com.intellij.openapi.vfs.LocalFileSystem
import com.jonnyzzz.intellij.mcp.storage.ExecutionStorage
import com.jonnyzzz.intellij.mcp.storage.ReviewOutcome
import kotlinx.coroutines.*
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration.Companion.seconds

/**
 * Result of a code review.
 */
sealed class ReviewResult {
    data object Approved : ReviewResult()

    /**
     * Rejected with user feedback.
     * @param originalCode The code originally submitted
     * @param editedCode The code after user edits (if any)
     * @param diff Unified diff showing changes (if code was modified)
     * @param codeWasModified True if user edited the code
     */
    data class Rejected(
        val originalCode: String,
        val editedCode: String,
        val diff: String?,
        val codeWasModified: Boolean
    ) : ReviewResult()

    data object Timeout : ReviewResult()
}

/**
 * Manages code review workflow.
 * Opens code in editor for human review before execution.
 * Users can edit the code to add comments or modifications.
 * On rejection, the edited code with diff is returned to the LLM.
 */
@Service(Service.Level.PROJECT)
class ReviewManager(private val project: Project) {
    private val log = Logger.getInstance(ReviewManager::class.java)

    // In-memory tracking of pending reviews
    private val pendingReviews = ConcurrentHashMap<String, PendingReview>()

    private val storage: ExecutionStorage
        get() = project.service()

    private data class PendingReview(
        val originalCode: String,
        val deferred: CompletableDeferred<ReviewResult>
    )

    /**
     * Request human review for code.
     * Opens code in editor and waits for approval/rejection.
     */
    suspend fun requestReview(executionId: String, code: String): ReviewResult {
        log.info("Requesting review for $executionId")

        // Check review mode
        val reviewMode = try {
            Registry.stringValue("mcp.steroids.review.mode")
        } catch (_: Exception) {
            "ALWAYS"
        }

        // TRUSTED or NEVER mode - auto-approve
        if (reviewMode == "TRUSTED" || reviewMode == "NEVER") {
            log.info("Auto-approving $executionId (review mode: $reviewMode)")
            return ReviewResult.Approved
        }

        // Save code for review in the execution directory
        val reviewFile = storage.saveReviewCode(executionId, code)

        // Open in editor on EDT
        val edtActionResult = withContext(Dispatchers.Main) {
            val vFile = LocalFileSystem.getInstance().refreshAndFindFileByPath(reviewFile.toString())
            if (vFile != null) {
                FileEditorManager.getInstance(project).openFile(vFile, true)
                null
            } else {
                log.warn("Could not open review file: $reviewFile")
                ReviewResult.Rejected(code, "Failed to open review file", null, false)
            }
        }
        if (edtActionResult != null) return edtActionResult

        // Create deferred for this review
        val deferred = CompletableDeferred<ReviewResult>()
        pendingReviews[executionId] = PendingReview(code, deferred)

        // Get timeout
        val timeoutSeconds = try {
            Registry.intValue("mcp.steroids.review.timeout")
        } catch (_: Exception) {
            300
        }

        // Wait for approval/rejection with timeout
        return try {
            withTimeout(timeoutSeconds.seconds) {
                deferred.await()
            }
        } catch (_: TimeoutCancellationException) {
            log.info("Review timeout for $executionId")
            pendingReviews.remove(executionId)
            ReviewResult.Timeout
        }
    }

    /**
     * Approve execution.
     * Called from the editor notification panel.
     */
    fun approve(executionId: String) {
        log.info("Approving $executionId")
        val pending = pendingReviews.remove(executionId) ?: return

        // Save review result
        storage.saveReviewResult(
            executionId, ReviewOutcome(
                approved = true,
                originalCode = pending.originalCode
            )
        )

        pending.deferred.complete(ReviewResult.Approved)
        closeReviewEditor(executionId)
    }

    /**
     * Reject execution.
     * Captures the edited code and generates a diff for LLM feedback.
     * Called from the editor notification panel.
     */
    fun reject(executionId: String) {
        log.info("Rejecting $executionId")
        val pending = pendingReviews.remove(executionId) ?: return

        val originalCode = pending.originalCode
        val editedCode = storage.readReviewCode(executionId) ?: originalCode
        val codeWasModified = originalCode != editedCode

        // Generate diff if code was modified
        val diff = if (codeWasModified) {
            generateUnifiedDiff(originalCode, editedCode)
        } else {
            null
        }

        // Save review result
        storage.saveReviewResult(
            executionId, ReviewOutcome(
                approved = false,
                originalCode = originalCode,
                editedCode = if (codeWasModified) editedCode else null,
                diff = diff
            )
        )

        pending.deferred.complete(
            ReviewResult.Rejected(
                originalCode = originalCode,
                editedCode = editedCode,
                diff = diff,
                codeWasModified = codeWasModified
            )
        )

        closeReviewEditor(executionId)
    }

    /**
     * Cancel a pending review (e.g., from MCP cancel request).
     */
    fun cancel(executionId: String) {
        log.info("Cancelling review for $executionId")
        val pending = pendingReviews.remove(executionId) ?: return

        val originalCode = pending.originalCode
        pending.deferred.complete(
            ReviewResult.Rejected(
                originalCode = originalCode,
                editedCode = originalCode,
                diff = null,
                codeWasModified = false
            )
        )

        closeReviewEditor(executionId)
    }

    /**
     * Check if there's a pending review for an execution.
     */
    fun hasPendingReview(executionId: String): Boolean {
        return pendingReviews.containsKey(executionId)
    }

    /**
     * Get execution ID from a review file path.
     */
    fun getExecutionIdFromPath(path: String): String? {
        // Path format: .../mcp-run/{execution-id}/review.kts
        if (!path.contains("mcp-run/") || !path.endsWith("/review.kts")) return null
        val parts = path.split("/")
        val mcpRunIndex = parts.indexOf("mcp-run")
        if (mcpRunIndex < 0 || mcpRunIndex + 1 >= parts.size) return null
        return parts[mcpRunIndex + 1]
    }

    private fun closeReviewEditor(executionId: String) {
        val reviewFile = storage.getReviewFilePath(executionId)
        val vFile = LocalFileSystem.getInstance().findFileByPath(reviewFile.toString())
        if (vFile != null) {
            FileEditorManager.getInstance(project).closeFile(vFile)
        }
    }

    /**
     * Generate a unified diff between original and edited code.
     */
    private fun generateUnifiedDiff(original: String, edited: String): String {
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
