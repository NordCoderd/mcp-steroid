/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.intellij.mcp.storage

import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.writeAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.jonnyzzz.intellij.mcp.server.ExecCodeParams
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.io.path.deleteIfExists
import kotlin.io.path.isRegularFile
import kotlin.io.path.writeText

@Serializable
data class ExecutionId(val executionId: String)

@Serializable
data class TextMessage(val text: String)

inline val Project.executionStorage : ExecutionStorage get() = service()

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
    private val log = thisLogger()

    val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
    }

    val oneLineJson = Json {
        prettyPrint = false
        ignoreUnknownKeys = true
    }

    private val baseDirExcluded = AtomicBoolean(false)

    private val baseDir: Path
        get() = Path.of(project.basePath ?: throw IllegalStateException("Project has no base path"), ".idea", "mcp-run")


    private val ExecutionId.dir: Path
        get() {
            val dir = baseDir.resolve(executionId)
            Files.createDirectories(dir)
            return dir
        }

    suspend fun appendExecutionEvent(executionId: ExecutionId, text: String) {
        appendExecutionEvent(executionId, TextMessage(text))
    }

    suspend inline fun <reified T> appendExecutionEvent(executionId: ExecutionId, message: T) {
        appendExecutionEventJson(executionId, oneLineJson.encodeToString(message))
    }

    suspend fun writeCodeErrorEvent(executionId: ExecutionId, text: String) {
        writeCodeExecutionData(executionId, "error.txt", text)
    }

    suspend fun appendExecutionEventJson(executionId: ExecutionId, json: String) {
        withContext(Dispatchers.IO) {
            val file = executionId.dir.resolve("output.jsonl")
            require(json.lines().size == 1)
            require(json.startsWith("{") && json.endsWith("}"))

            Files.writeString(
                file,
                json + "\n",
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND
            )
        }
    }

    suspend inline fun <reified T> writeCodeExecutionData(executionId: ExecutionId, name: String, data: T) {
        writeCodeExecutionData(executionId, name, json.encodeToString(data))
    }

    suspend fun writeCodeExecutionData(executionId: ExecutionId, name: String, data: String): Path {
        val path = executionId.dir.resolve(name)
        withContext(Dispatchers.IO) {
            ensureBaseDirExcluded()
            path.writeText(data)
        }
        return path
    }

    fun findExecutionId(executionId: String) : ExecutionId? {
        if (executionId.contains("/") || executionId.contains("..")) return null

        val path = baseDir.resolve(executionId).resolve("params.json")
        if (!path.isRegularFile()) return null

        return ExecutionId(executionId)
    }


    private fun newExecutionId(taskId: String): ExecutionId {
        val pattern = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss")
        val timestamp = LocalDateTime.now().format(pattern)
        val invalidPath = Regex("[^a-zA-Z0-9_-]+", RegexOption.IGNORE_CASE)
        val id = "eid_" + timestamp + "-" + invalidPath.replace(taskId, "_")
        return ExecutionId(id)
    }

    suspend fun writeNewExecution(exec: ExecCodeParams) : ExecutionId {
        val storage = project.executionStorage

        val executionId = storage.newExecutionId(exec.taskId)
        storage.writeCodeExecutionData(executionId, "params.json", exec.rawParams)
        storage.writeCodeExecutionData(executionId, "reason.txt", exec.reason)
        storage.writeCodeExecutionData(executionId, "script.kts", exec.code)
        storage.writeCodeExecutionData(executionId, "execution-id.txt", executionId.executionId)

        return executionId
    }

    suspend fun writeWrappedScript(executionId: ExecutionId, code: String) {
        writeCodeExecutionData(executionId, "script-wrapped.kts", code)
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
                        baseDirExcluded.set(false)
                    } finally {
                        modifiableModel.dispose()
                    }
                }
                return
            }
        }
        log.debug("mcp-run folder is not under any content root, no exclusion needed")
    }

    suspend fun writeCodeReviewFile(executionId: ExecutionId, codeForReview: String): Path {
        return writeCodeExecutionData(executionId, "review.kts", codeForReview)
    }

    suspend fun removeCodeReviewFile(executionId: ExecutionId) {
        withContext(Dispatchers.IO) {
            runCatching {
                executionId.dir.resolve("review.kts").deleteIfExists()
            }
        }
    }
}
