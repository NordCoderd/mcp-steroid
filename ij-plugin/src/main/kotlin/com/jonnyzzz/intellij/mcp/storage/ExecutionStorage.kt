/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.intellij.mcp.storage

import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.writeAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.vfs.LocalFileSystem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.*
import java.util.concurrent.atomic.AtomicBoolean

@Serializable
enum class OutputType {
    COMPILER, //output from the compiler
    OUT,   // stdout from println()
    JSON,  // structured data from printJson()
    LOG,   // log messages (info/warn/error)
    ERR    // exceptions/errors
}

@Serializable
data class OutputMessage(
    val ts: Long,
    val type: OutputType,
    val msg: String,
    val level: String? = null  // For LOG type: info, warn, error
)

@Serializable
data class ExecutionParams(
    val timeout: Int? = 120,
    val showReviewOnError: Boolean = false
)

@Serializable
enum class ExecutionStatus {
    SUBMITTED,
    PENDING_REVIEW,
    RUNNING,
    SUCCESS,
    ERROR,
    TIMEOUT,
    CANCELLED,
    REJECTED,
    NOT_FOUND
}

@Serializable
data class ExecutionResult(
    val status: ExecutionStatus,
    val errorMessage: String? = null,
    val exceptionInfo: String? = null
)

/**
 * File-based storage for execution history.
 * APPEND-ONLY: Files are never deleted, only added.
 *
 * Directory structure:
 * .idea/mcp-run/
 *   {execution-id}/
 *     script.kts          - Original code submitted by LLM
 *     parameters.json     - Execution parameters
 *     output.jsonl        - Output messages (append-only)
 *     result.json         - Final execution result
 *     review.kts          - Code shown for review (may have user edits)
 *     review-result.json  - Review outcome with user feedback
 */
@Service(Service.Level.PROJECT)
class ExecutionStorage(
    private val project: Project,
    private val coroutineScope: CoroutineScope,
) {
    private val log = Logger.getInstance(ExecutionStorage::class.java)

    private val json = Json {
        prettyPrint = false
        ignoreUnknownKeys = true
    }

    private val baseDirExcluded = AtomicBoolean(false)

    private val baseDir: Path
        get() = Path.of(project.basePath ?: throw IllegalStateException("Project has no base path"), ".idea", "mcp-run")

    /**
     * Generate execution ID in format: {project-hash-3}-{YYYY-MM-DD}T{HH-MM-SS}-{payload-hash-10}
     */
    fun generateExecutionId(code: String, params: ExecutionParams): String {
        val projectHash = computeHash(project.name).take(3)
        val timestamp = LocalDateTime.now()
            .format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH-mm-ss"))
        val payloadHash = computeHash(code + json.encodeToString(params)).take(10)

        return "$projectHash-$timestamp-$payloadHash"
    }

    private fun computeHash(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hashBytes = digest.digest(input.toByteArray(Charsets.UTF_8))
        return Base64.getUrlEncoder().withoutPadding().encodeToString(hashBytes)
    }

    /**
     * Create execution directory and save initial script file.
     * This is called immediately when execution is submitted.
     */
    fun createExecution(executionId: String, code: String, params: ExecutionParams) {
        val dir = baseDir.resolve(executionId)
        Files.createDirectories(dir)

        // Save original script as .kts file
        Files.writeString(dir.resolve("script.kts"), code)
        Files.writeString(dir.resolve("parameters.json"), json.encodeToString(params))

        // Mark the mcp-run folder as excluded to disable error highlighting
        ensureBaseDirExcluded()
    }

    /**
     * Mark the mcp-run folder as excluded from indexing.
     * This prevents error highlighting on .kts files in this folder.
     * The exclusion is done lazily and only once per project session.
     */
    private fun ensureBaseDirExcluded() {
        // Only try to exclude once
        if (!baseDirExcluded.compareAndSet(false, true)) return

        coroutineScope.launch {
            try {
                withContext(Dispatchers.EDT) {
                    excludeBaseDirOnEdt()
                }
            } catch (e: Exception) {
                log.warn("Failed to mark mcp-run folder as excluded: ${e.message}", e)
                baseDirExcluded.set(false)
            }
        }
    }

    private suspend fun excludeBaseDirOnEdt() {
        val baseDirPath = baseDir.toString()
        val vFile = LocalFileSystem.getInstance().refreshAndFindFileByPath(baseDirPath)
        if (vFile == null) {
            log.warn("Cannot find mcp-run directory to exclude: $baseDirPath")
            baseDirExcluded.set(false)
            return
        }

        // Find the module that contains this directory
        val modules = ModuleManager.getInstance(project).modules
        for (module in modules) {
            val rootManager = ModuleRootManager.getInstance(module)

            // Check if any content entry contains our directory
            for (contentEntry in rootManager.contentEntries) {
                val contentRoot = contentEntry.file ?: continue
                if (!vFile.path.startsWith(contentRoot.path)) continue

                // Check if already excluded
                val excludedFolders = contentEntry.excludeFolders
                if (excludedFolders.any { it.url == vFile.url }) {
                    log.info("mcp-run folder already excluded")
                    return
                }

                // Exclude the folder - must be done in writeAction
                writeAction {
                    val modifiableModel = rootManager.modifiableModel
                    try {
                        for (entry in modifiableModel.contentEntries) {
                            val entryRoot = entry.file ?: continue
                            if (vFile.path.startsWith(entryRoot.path)) {
                                entry.addExcludeFolder(vFile)
                                log.info("Excluded mcp-run folder: $baseDirPath")
                                break
                            }
                        }
                        modifiableModel.commit()
                    } catch (e: Exception) {
                        log.warn("Failed to exclude mcp-run folder: ${e.message}", e)
                        modifiableModel.dispose()
                        baseDirExcluded.set(false)
                    }
                }
                return
            }
        }
        log.debug("mcp-run folder is not under any content root, no exclusion needed")
    }

    /**
     * Save code for review.
     * Creates a review.kts file that the user can edit.
     * Returns the path to the review file.
     */
    fun saveReviewCode(executionId: String, code: String): Path {
        val dir = baseDir.resolve(executionId)
        Files.createDirectories(dir)
        val file = dir.resolve("review.kts")
        Files.writeString(file, code)
        return file
    }

    /**
     * Read the current review code (which may have user edits).
     */
    fun readReviewCode(executionId: String): String? {
        val file = baseDir.resolve(executionId).resolve("review.kts")
        if (!Files.exists(file)) return null
        return Files.readString(file)
    }

    /**
     * Save review result (approved/rejected with optional user feedback).
     */
    fun saveReviewResult(executionId: String, result: ReviewOutcome) {
        val file = baseDir.resolve(executionId).resolve("review-result.json")
        Files.writeString(file, json.encodeToString(result))
    }

    /**
     * Read review result.
     */
    fun readReviewResult(executionId: String): ReviewOutcome? {
        val file = baseDir.resolve(executionId).resolve("review-result.json")
        if (!Files.exists(file)) return null
        return json.decodeFromString<ReviewOutcome>(Files.readString(file))
    }

    /**
     * Append an output message to the execution log.
     */
    fun appendOutput(executionId: String, message: OutputMessage) {
        val file = baseDir.resolve(executionId).resolve("output.jsonl")
        val line = json.encodeToString(message) + "\n"
        Files.writeString(
            file,
            line,
            StandardOpenOption.CREATE,
            StandardOpenOption.APPEND
        )
    }

    fun appendOutput(executionId: String, type: OutputType, message: String, level: String? = null) {
        appendOutput(
            executionId,
            OutputMessage(
                ts = System.currentTimeMillis(),
                type = type,
                msg = message,
                level = level
            )
        )
    }

    /**
     * Read output messages, optionally skipping first N.
     */
    fun readOutput(executionId: String, offset: Int = 0): List<OutputMessage> {
        val file = baseDir.resolve(executionId).resolve("output.jsonl")
        if (!Files.exists(file)) return emptyList()

        return Files.readAllLines(file)
            .drop(offset)
            .filter { it.isNotBlank() }
            .map { json.decodeFromString<OutputMessage>(it) }
    }

    /**
     * Write final execution result.
     */
    fun writeResult(executionId: String, result: ExecutionResult) {
        val file = baseDir.resolve(executionId).resolve("result.json")
        Files.writeString(file, json.encodeToString(result))
    }

    /**
     * Read execution result if available.
     */
    fun readResult(executionId: String): ExecutionResult? {
        val file = baseDir.resolve(executionId).resolve("result.json")
        if (!Files.exists(file)) return null
        return json.decodeFromString<ExecutionResult>(Files.readString(file))
    }

    /**
     * Read the original script code for an execution.
     */
    fun readScript(executionId: String): String? {
        val file = baseDir.resolve(executionId).resolve("script.kts")
        if (!Files.exists(file)) return null
        return Files.readString(file)
    }

    /**
     * Read execution parameters.
     */
    fun readParams(executionId: String): ExecutionParams? {
        val file = baseDir.resolve(executionId).resolve("parameters.json")
        if (!Files.exists(file)) return null
        return json.decodeFromString<ExecutionParams>(Files.readString(file))
    }

    /**
     * Check if execution exists.
     */
    fun exists(executionId: String): Boolean {
        return Files.exists(baseDir.resolve(executionId))
    }

    /**
     * Get execution directory path.
     */
    fun getExecutionDir(executionId: String): Path {
        return baseDir.resolve(executionId)
    }

    /**
     * Get path to review file for an execution.
     */
    fun getReviewFilePath(executionId: String): Path {
        return baseDir.resolve(executionId).resolve("review.kts")
    }
}

/**
 * Review outcome stored after user approves/rejects.
 */
@Serializable
data class ReviewOutcome(
    val approved: Boolean,
    val originalCode: String,
    val editedCode: String? = null,
    val userComments: String? = null,
    val diff: String? = null,
    val timestamp: Long = System.currentTimeMillis()
)
