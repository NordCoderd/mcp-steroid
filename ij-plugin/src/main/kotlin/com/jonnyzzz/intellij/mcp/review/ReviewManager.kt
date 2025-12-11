/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.intellij.mcp.review

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.registry.Registry
import com.intellij.openapi.vfs.LocalFileSystem
import com.jonnyzzz.intellij.mcp.storage.ExecutionStorage
import kotlinx.coroutines.*
import java.util.concurrent.ConcurrentHashMap

/**
 * Result of a code review.
 */
sealed class ReviewResult {
    data object Approved : ReviewResult()
    data class Rejected(val reason: String, val editedCode: String? = null) : ReviewResult()
    data object Timeout : ReviewResult()
}

/**
 * Manages code review workflow.
 * Opens code in editor for human review before execution.
 */
@Service(Service.Level.PROJECT)
class ReviewManager(private val project: Project) {
    private val log = Logger.getInstance(ReviewManager::class.java)

    private val pendingReviews = ConcurrentHashMap<String, CompletableDeferred<ReviewResult>>()

    private val storage: ExecutionStorage
        get() = project.service()

    /**
     * Request human review for code.
     * Opens code in editor and waits for approval/rejection.
     */
    suspend fun requestReview(executionId: String, code: String): ReviewResult {
        log.info("Requesting review for $executionId")

        // Check review mode
        val reviewMode = try {
            Registry.stringValue("mcp.steroids.review.mode")
        } catch (e: Exception) {
            "ALWAYS"
        }

        // TRUSTED or NEVER mode - auto-approve
        if (reviewMode == "TRUSTED" || reviewMode == "NEVER") {
            log.info("Auto-approving $executionId (review mode: $reviewMode)")
            return ReviewResult.Approved
        }

        // Save code for review
        val reviewFile = storage.savePendingReview(executionId, code)

        // Open in editor on EDT
        withContext(Dispatchers.Main) {
            val vFile = LocalFileSystem.getInstance().refreshAndFindFileByPath(reviewFile.toString())
            if (vFile != null) {
                FileEditorManager.getInstance(project).openFile(vFile, true)
            } else {
                log.warn("Could not open review file: $reviewFile")
            }
        }

        // Create deferred for this review
        val deferred = CompletableDeferred<ReviewResult>()
        pendingReviews[executionId] = deferred

        // Get timeout
        val timeoutSeconds = try {
            Registry.intValue("mcp.steroids.review.timeout")
        } catch (e: Exception) {
            300
        }

        // Wait for approval/rejection with timeout
        return try {
            withTimeout(timeoutSeconds * 1000L) {
                deferred.await()
            }
        } catch (e: TimeoutCancellationException) {
            log.info("Review timeout for $executionId")
            cleanup(executionId)
            ReviewResult.Timeout
        } finally {
            pendingReviews.remove(executionId)
        }
    }

    /**
     * Approve execution.
     * Called from the editor notification panel.
     */
    fun approve(executionId: String) {
        log.info("Approving $executionId")
        pendingReviews[executionId]?.complete(ReviewResult.Approved)
        cleanup(executionId)
    }

    /**
     * Reject execution.
     * Called from the editor notification panel.
     */
    fun reject(executionId: String, reason: String = "Rejected by user", editedCode: String? = null) {
        log.info("Rejecting $executionId: $reason")
        pendingReviews[executionId]?.complete(ReviewResult.Rejected(reason, editedCode))
        cleanup(executionId)
    }

    /**
     * Cancel a pending review.
     */
    fun cancel(executionId: String) {
        log.info("Cancelling review for $executionId")
        pendingReviews[executionId]?.complete(ReviewResult.Rejected("Cancelled"))
        cleanup(executionId)
    }

    /**
     * Check if there's a pending review for an execution.
     */
    fun hasPendingReview(executionId: String): Boolean {
        return pendingReviews.containsKey(executionId)
    }

    /**
     * Get execution ID from a pending review file path.
     */
    fun getExecutionIdFromPath(path: String): String? {
        if (!path.contains("mcp-run/pending/")) return null
        val fileName = path.substringAfterLast("/")
        if (!fileName.endsWith(".kt")) return null
        return fileName.removeSuffix(".kt")
    }

    private fun cleanup(executionId: String) {
        storage.removePendingReview(executionId)
        // Close the editor tab
        val reviewFile = storage.getExecutionDir(executionId).parent.resolve("pending/$executionId.kt")
        val vFile = LocalFileSystem.getInstance().findFileByPath(reviewFile.toString())
        if (vFile != null) {
            FileEditorManager.getInstance(project).closeFile(vFile)
        }
    }
}
